package com.bailensn.sort.ui.table

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bailensn.sort.table.TableGrid
import com.bailensn.sort.table.TablePipeline
import com.bailensn.sort.ui.table.TableUiState.Phase
import com.bailensn.sort.ocr.ModelDownloadManager
import com.bailensn.sort.ocr.ModelRepository
import com.bailensn.sort.util.NetworkStatus
import com.bailensn.sort.util.ImageLoader
import com.bailensn.sort.xlsx.ExportedFile
import com.bailensn.sort.xlsx.XlsxExporter
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface EngineState {
    /** 首次启动会先下载模型（约 140 MB），[message] 用于展示进度。 */
    data class Loading(
        val message: String = "正在加载识别模型",
        /** 下载进度 0..1；null 表示不确定进度（加载模型、等确认）。 */
        val progress: Float? = null,
    ) : EngineState
    data class Ready(
        val loadTimeMs: Long,
        /** true = medium 没下成，正在用 APK 内置的轻量模型。 */
        val builtIn: Boolean = false,
    ) : EngineState
    data class Failed(val message: String) : EngineState
}

data class TableUiState(
    val engine: EngineState = EngineState.Loading(),
    /** 是否要弹「用移动数据下载」确认框（不影响已就绪的引擎）。 */
    val downloadPrompt: Boolean = false,
    val original: Bitmap? = null,
    val processed: Bitmap? = null,
    val overlay: Bitmap? = null,
    val phase: Phase = Phase.IDLE,
    val stage: TablePipeline.Stage? = null,
    val grid: TableGrid? = null,
    val message: String? = null,
    val elapsedMs: Long = 0L,
    val deskewAngle: Double = 0.0,
    val rectified: Boolean = false,
    val correctedCells: Int = 0,
    val exporting: Boolean = false,
) {
    enum class Phase { IDLE, RECOGNIZING, DONE, ERROR }
}

class TableViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(TableUiState())
    val state: StateFlow<TableUiState> = _state.asStateFlow()

    private val _exported = MutableSharedFlow<ExportedFile>(extraBufferCapacity = 1)
    val exported: SharedFlow<ExportedFile> = _exported.asSharedFlow()

    private var engine: PaddleOCR? = null
    private var engineDeferred = CompletableDeferred<PaddleOCR>()

    init {
        loadEngine()
    }

    fun retryEngine() {
        if (_state.value.engine is EngineState.Loading) return
        loadEngine()
    }

    private var downloadApproval: CompletableDeferred<Boolean>? = null

    /** 用户在确认框里点了「继续下载」。 */
    fun approveDownload() {
        ModelDownloadManager.setAllowMetered(true)
        downloadApproval?.complete(true)
    }

    /** 用户选择「连 Wi-Fi 再下」。 */
    fun declineDownload() {
        downloadApproval?.complete(false)
    }

    private suspend fun awaitDownloadApproval(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        downloadApproval = deferred
        _state.update { it.copy(downloadPrompt = true) }
        val approved = deferred.await()
        downloadApproval = null
        _state.update { it.copy(downloadPrompt = false) }
        return approved
    }

    private fun loadEngine() {
        engineDeferred = CompletableDeferred()
        _state.update { it.copy(engine = EngineState.Loading("正在加载模型"), message = null) }

        viewModelScope.launch {
            val app = getApplication<Application>()
            ModelDownloadManager.refresh(app)
            try {
                if (!OpenCVUtils.init(app)) {
                    error("OpenCV 初始化失败")
                }
                val config = PaddleOCRConfig(recScoreThresh = 0f, recBatchSize = 1)
                val downloaded = ModelRepository.downloadedProfile(app)

                // 先用手上有的模型把引擎拉起来，绝不让下载卡住启动。
                val created = withContext(Dispatchers.IO) {
                    if (downloaded != null) {
                        PaddleOCR.createWithModels(app, ModelRepository.modelDir(app), config)
                    } else {
                        PaddleOCR.create(context = app, config = config)
                    }
                }
                engine = created
                engineDeferred.complete(created)
                _state.update {
                    it.copy(
                        engine = EngineState.Ready(
                            loadTimeMs = created.coldLoadTimeMs,
                            builtIn = downloaded == null,
                        ),
                    )
                }

                if (downloaded == null) {
                    startModelDownload(app, config)
                }
            } catch (t: Throwable) {
                engineDeferred.completeExceptionally(t)
                _state.update {
                    it.copy(engine = EngineState.Failed(t.message ?: "识别模型加载失败"))
                }
            }
        }
    }

    /** 下载高精度模型：能下就下，下完热切换；失败只影响状态行，不影响识别。 */
    private fun startModelDownload(app: Application, config: PaddleOCRConfig) {
        viewModelScope.launch {
            if (!NetworkStatus.isOnline(app)) {
                ModelDownloadManager.startBackground(app)
                return@launch
            }
            if (NetworkStatus.isMetered(app)) {
                if (!awaitDownloadApproval()) {
                    ModelDownloadManager.startBackground(app)
                    return@launch
                }
                ModelDownloadManager.setAllowMetered(true)
            }
            val dir = ModelDownloadManager.downloadForeground(app) { _, _ -> }
            if (dir != null) {
                upgradeEngine(app, dir, config)
            }
        }
    }

    private suspend fun upgradeEngine(app: Application, modelDir: File, config: PaddleOCRConfig) {
        // 正在识别时不换引擎，等下次启动自然用上高精度模型。
        if (_state.value.phase != TableUiState.Phase.IDLE) return
        val upgraded = withContext(Dispatchers.IO) {
            PaddleOCR.createWithModels(app, modelDir, config)
        }
        val previous = engine
        engine = upgraded
        val deferred = CompletableDeferred<PaddleOCR>()
        deferred.complete(upgraded)
        engineDeferred = deferred
        _state.update {
            it.copy(engine = EngineState.Ready(upgraded.coldLoadTimeMs, builtIn = false))
        }
        runCatching { previous?.release() }
    }

    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            _state.update {
                it.copy(
                    original = null,
                    processed = null,
                    overlay = null,
                    grid = null,
                    phase = Phase.RECOGNIZING,
                    stage = TablePipeline.Stage.RECTIFY,
                    message = null,
                    elapsedMs = 0L,
                    correctedCells = 0,
                )
            }
            try {
                val bitmap = withContext(Dispatchers.IO) { ImageLoader.decode(app, uri) }
                _state.update { it.copy(original = bitmap) }
                runPipeline(bitmap)
            } catch (t: Throwable) {
                _state.update {
                    it.copy(phase = Phase.ERROR, message = t.message ?: "无法读取图片", stage = null)
                }
            }
        }
    }

    fun retryRecognition() {
        val original = _state.value.original ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = Phase.RECOGNIZING,
                    stage = TablePipeline.Stage.RECTIFY,
                    message = null,
                    grid = null,
                )
            }
            runPipeline(original)
        }
    }

    private suspend fun runPipeline(bitmap: Bitmap) {
        try {
            val ocr = engineDeferred.await()
            val analysis = withContext(Dispatchers.Default) {
                TablePipeline.analyze(ocr, bitmap) { stage ->
                    _state.update { it.copy(stage = stage) }
                }
            }

            if (analysis.grid.isEmpty) {
                _state.update {
                    it.copy(
                        phase = Phase.ERROR,
                        stage = null,
                        message = "没有识别到表格，换一张清晰的照片试试",
                        processed = analysis.bitmap,
                        overlay = analysis.overlay,
                        elapsedMs = analysis.elapsedMs,
                        deskewAngle = analysis.deskewAngle,
                        rectified = analysis.rectified,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        phase = Phase.DONE,
                        stage = null,
                        grid = analysis.grid,
                        processed = analysis.bitmap,
                        overlay = analysis.overlay,
                        elapsedMs = analysis.elapsedMs,
                        deskewAngle = analysis.deskewAngle,
                        rectified = analysis.rectified,
                        correctedCells = analysis.correctedCells,
                    )
                }
            }
        } catch (t: Throwable) {
            _state.update {
                it.copy(phase = Phase.ERROR, stage = null, message = t.message ?: "识别失败")
            }
        }
    }

    fun exportXlsx() {
        val grid = _state.value.grid ?: return
        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            try {
                val exported = withContext(Dispatchers.IO) {
                    XlsxExporter.export(getApplication(), grid)
                }
                _exported.emit(exported)
            } catch (t: Throwable) {
                _state.update { it.copy(message = "导出失败：${t.message.orEmpty()}") }
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    fun reset() {
        _state.update {
            it.copy(
                original = null,
                processed = null,
                overlay = null,
                grid = null,
                phase = Phase.IDLE,
                stage = null,
                message = null,
                elapsedMs = 0L,
                correctedCells = 0,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        val current = engine ?: return
        engine = null
        // viewModelScope 此时已经被取消，释放 native 资源要用独立作用域。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { current.release() }
        }
    }
}
