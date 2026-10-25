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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    /** 当前是移动数据，等用户确认是否继续下载模型。 */
    data class AwaitingDownload(val message: String) : EngineState
    data class Ready(
        val loadTimeMs: Long,
        /** true = medium 没下成，正在用 APK 内置的轻量模型。 */
        val builtIn: Boolean = false,
    ) : EngineState
    data class Failed(val message: String) : EngineState
}

data class TableUiState(
    val engine: EngineState = EngineState.Loading(),
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

    /**
     * 尝试把高精度模型下载下来；任何一步不满足就返回 null，让调用方用内置模型。
     * 不抛异常——下载失败不应该让整个识别功能不可用。
     */
    /** 用户等得不耐烦时点「转到后台下载」：前台立刻收工，后台继续。 */
    fun switchToBackground() {
        val app = getApplication<Application>()
        foregroundDownload?.cancel()
        foregroundDownload = null
        _state.update {
            it.copy(engine = EngineState.Loading("已转到后台下载，本次先用内置模型"))
        }
        ModelDownloadManager.startBackground(app)
    }

    private suspend fun tryDownloadModels(app: Application): File? = coroutineScope {
        if (!NetworkStatus.isOnline(app)) {
            // 没网：转后台等网络恢复，前台直接用内置模型。
            ModelDownloadManager.startBackground(app)
            _state.update { it.copy(engine = EngineState.Loading("没有网络，先用内置轻量模型")) }
            return@coroutineScope null
        }
        if (NetworkStatus.isMetered(app) && !awaitDownloadApproval()) {
            ModelDownloadManager.startBackground(app)
            return@coroutineScope null
        }

        val job = async(Dispatchers.IO) {
            ModelRepository.ensure(app) { percent, text ->
                ModelDownloadManager.reportDownloading(percent, text)
                _state.update {
                    it.copy(
                        engine = EngineState.Loading(
                            message = text,
                            progress = (percent / 100f).coerceIn(0f, 1f),
                        ),
                    )
                }
            }
        }
        foregroundDownload = job
        try {
            val dir = job.await()
            ModelDownloadManager.reportReady()
            dir
        } catch (c: CancellationException) {
            // 用户主动转后台：本次用内置模型，后台接着下。
            null
        } catch (t: Throwable) {
            ModelDownloadManager.afterForegroundAttempt(app, success = false, message = t.message)
            null
        } finally {
            foregroundDownload = null
        }
    }

    private var foregroundDownload: Job? = null

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
        _state.update {
            it.copy(engine = EngineState.AwaitingDownload("首次使用需下载约 140 MB 模型，当前是移动数据"))
        }
        val approved = deferred.await()
        downloadApproval = null
        return approved
    }

    private fun loadEngine() {
        engineDeferred = CompletableDeferred()
        _state.update { it.copy(engine = EngineState.Loading("正在准备识别模型"), message = null) }

        viewModelScope.launch {
            val app = getApplication<Application>()
            ModelDownloadManager.refresh(app)
            try {
                if (!OpenCVUtils.init(app)) {
                    error("OpenCV 初始化失败")
                }
                val config = PaddleOCRConfig(recScoreThresh = 0f, recBatchSize = 1)

                // 高精度模型优先：已下载就直接用；没下载就按网络情况尝试下载；
                // 下载失败、没网、用户选择不烧流量，都退回 APK 内置的轻量模型。
                val modelDir = if (ModelRepository.isReady(app)) {
                    ModelRepository.modelDir(app)
                } else {
                    tryDownloadModels(app)
                }

                _state.update {
                    it.copy(
                        engine = EngineState.Loading(
                            message = if (modelDir == null) "正在加载内置轻量模型" else "正在加载识别模型",
                        ),
                        message = null,
                    )
                }

                val created = withContext(Dispatchers.IO) {
                    if (modelDir != null) {
                        PaddleOCR.createWithModels(
                            context = app,
                            modelDir = modelDir,
                            config = config,
                        )
                    } else {
                        PaddleOCR.create(context = app, config = config)
                    }
                }
                engine = created
                engineDeferred.complete(created)
                if (modelDir == null) {
                    // 这次用内置模型顶着，后台继续尝试下高精度模型。
                    ModelDownloadManager.startBackground(app)
                }
                _state.update {
                    it.copy(
                        engine = EngineState.Ready(
                            loadTimeMs = created.coldLoadTimeMs,
                            builtIn = modelDir == null,
                        ),
                    )
                }
            } catch (t: Throwable) {
                engineDeferred.completeExceptionally(t)
                _state.update {
                    it.copy(engine = EngineState.Failed(t.message ?: "识别模型加载失败"))
                }
            }
        }
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
