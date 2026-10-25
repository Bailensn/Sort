package com.bailensn.sort.ui.table

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bailensn.sort.table.TableGrid
import com.bailensn.sort.table.TablePipeline
import com.bailensn.sort.ui.table.TableUiState.Phase
import com.bailensn.sort.util.ImageLoader
import com.bailensn.sort.ocr.ModelInstaller
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

sealed interface EngineState {
    /** 首次启动会先下载模型（约 140 MB），[message] 用于展示进度。 */
    data class Loading(val message: String = "正在加载识别模型") : EngineState
    data class Ready(val loadTimeMs: Long) : EngineState
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
    val horizontalLines: Int = 0,
    val verticalLines: Int = 0,
    val cellCount: Int = 0,
    val cellMs: Long = 0L,
    val detectMs: Long = 0L,
    val enhanced: Bitmap? = null,
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

    private fun loadEngine() {
        engineDeferred = CompletableDeferred()
        _state.update { it.copy(engine = EngineState.Loading("正在加载识别模型"), message = null) }

        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                if (!OpenCVUtils.init(app)) {
                    error("OpenCV 初始化失败")
                }
                val modelDir = ModelInstaller.ensure(app) { stage ->
                    _state.update { it.copy(engine = EngineState.Loading(stage)) }
                }
                _state.update { it.copy(engine = EngineState.Loading("正在加载识别模型")) }
                val created = withContext(Dispatchers.IO) {
                    PaddleOCR.createWithModels(
                        context = app,
                        modelDir = modelDir,
                        config = PaddleOCRConfig(recScoreThresh = 0f, recBatchSize = 1),
                    )
                }
                engine = created
                engineDeferred.complete(created)
                _state.update { it.copy(engine = EngineState.Ready(created.coldLoadTimeMs)) }
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

            // 一行都没读到也是失败：界面上会摆出一张空表，看着像「渲染成空白」。
            val blank = analysis.grid.isEmpty || analysis.grid.filledCellCount == 0
            if (blank) {
                val hint = if (analysis.cellCount == 0) {
                    "没找到表格线（横线 ${analysis.horizontalLines}、竖线 ${analysis.verticalLines}）"
                } else {
                    "切出 ${analysis.cellCount} 个格子但一个字都没认出来"
                }
                _state.update {
                    it.copy(
                        phase = Phase.ERROR,
                        stage = null,
                        message = "这张图识别不出表格：$hint。换一张、或点「看网格」看看校正结果。",
                        processed = analysis.bitmap,
                        overlay = analysis.overlay,
                        elapsedMs = analysis.elapsedMs,
                        deskewAngle = analysis.deskewAngle,
                        rectified = analysis.rectified,
                        horizontalLines = analysis.horizontalLines,
                        verticalLines = analysis.verticalLines,
                        cellCount = analysis.cellCount,
                        cellMs = analysis.cellMs,
                        detectMs = analysis.detectMs,
                        enhanced = analysis.enhanced,
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
                        horizontalLines = analysis.horizontalLines,
                        verticalLines = analysis.verticalLines,
                        cellCount = analysis.cellCount,
                        cellMs = analysis.cellMs,
                        detectMs = analysis.detectMs,
                        enhanced = analysis.enhanced,
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
