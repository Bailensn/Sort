package com.bailensn.sort.ui.table

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bailensn.sort.data.HistoryStore
import com.bailensn.sort.data.NameMemory
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
    val averageConfidence: Float = 0f,
    val lowConfidenceCells: Int = 0,
    val selfCheckSummary: String = "",
    val suspiciousRows: List<Int> = emptyList(),
) {
    enum class Phase { IDLE, RECOGNIZING, DONE, ERROR }
}

class TableViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(TableUiState())
    val state: StateFlow<TableUiState> = _state.asStateFlow()

    private val _exported = MutableSharedFlow<ExportedFile>(extraBufferCapacity = 1)
    val exported: SharedFlow<ExportedFile> = _exported.asSharedFlow()

    private val _history = MutableStateFlow<List<HistoryStore.Entry>>(emptyList())
    val history: StateFlow<List<HistoryStore.Entry>> = _history.asStateFlow()

    // 手改时的候选名：只读一次盘，改完再刷，避免在组合期做 IO。
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    /** 当前结果对应的历史条目文件名；手改格子时回写它。 */
    private var currentEntryId: String? = null

    private var engine: PaddleOCR? = null
    private var engineDeferred = CompletableDeferred<PaddleOCR>()

    init {
        loadEngine()
        _suggestions.value = NameMemory.load(getApplication())
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
                        config = PaddleOCRConfig(
                            recScoreThresh = 0f,
                            // 整图那条路每张图有几百行文字，批 1 等于几百次推理；
                            // 批 16 之后耗时从 30 s 掉到个位数，结果不打折。
                            recBatchSize = 16,
                            // 校对用的整图检测：1600 宽足够定位格子，比全分辨率(1920)省一截，
                            // 框坐标会被引擎换算回原图，对校对结果没有实质影响。
                            detMaxSideLimit = 1600,
                        ),
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
                        averageConfidence = analysis.averageConfidence,
                        lowConfidenceCells = analysis.lowConfidenceCells,
                        selfCheckSummary = analysis.selfCheck.summary,
                        suspiciousRows = analysis.selfCheck.suspiciousRows,
                    )
                }
            } else {
                val polished = applyNameMemory(analysis.grid)
                currentEntryId = HistoryStore.save(getApplication(), polished.rows, bitmap)?.id
                _history.value = HistoryStore.list(getApplication())
                _state.update {
                    it.copy(
                        phase = Phase.DONE,
                        stage = null,
                        grid = polished,
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
                        averageConfidence = analysis.averageConfidence,
                        lowConfidenceCells = analysis.lowConfidenceCells,
                        selfCheckSummary = analysis.selfCheck.summary,
                        suspiciousRows = analysis.selfCheck.suspiciousRows,
                    )
                }
            }
        } catch (t: Throwable) {
            _state.update {
                it.copy(phase = Phase.ERROR, stage = null, message = t.message ?: "识别失败")
            }
        }
    }

    private fun applyNameMemory(grid: TableGrid): TableGrid {
        if (grid.rows.isEmpty()) return grid
        val memory = NameMemory.load(getApplication())
        if (memory.isEmpty()) return grid
        val width = grid.columnCount
        val nameColumns = (0 until width).filter { column ->
            val values = grid.rows.mapNotNull { row -> row.getOrNull(column)?.takeIf(String::isNotBlank) }
            values.isNotEmpty() && values.count { value -> value.any { it.code > 0x2E80 } } >= values.size * 0.6
        }
        if (nameColumns.isEmpty()) return grid
        val rows = grid.rows.map { it.toMutableList() }
        nameColumns.forEach { column ->
            rows.forEach { row ->
                val value = row.getOrNull(column)?.trim().orEmpty()
                if (value.length !in 2..4) return@forEach
                val match = NameMemory.nearest(value, memory) ?: return@forEach
                if (match != value) row[column] = match
            }
        }
        return grid.copy(rows = rows)
    }

    /** 单格手改：用户在预览里点开某一格改字，直接作用于当前结果。 */
    fun updateCell(row: Int, column: Int, text: String) {
        val grid = _state.value.grid ?: return
        if (row !in grid.rows.indices) return
        val newRows = grid.rows.map { it.toMutableList() }
        if (column !in newRows[row].indices) return
        newRows[row][column] = text.trim()
        _state.update { it.copy(grid = grid.copy(rows = newRows)) }
        // 改完立刻落盘：下次从历史记录进来，看到的还是改过的版本。
        currentEntryId?.let { entryId ->
            HistoryStore.update(getApplication(), entryId, newRows)
            _history.value = HistoryStore.list(getApplication())
        }
        // 改对了的名字记下来，下次自动纠。
        val value = text.trim()
        if (value.length in 2..4 && value.all { it.code > 0x2E80 }) {
            NameMemory.remember(getApplication(), listOf(value))
            _suggestions.value = NameMemory.load(getApplication())
        }
    }

    fun refreshHistory() {
        _history.value = HistoryStore.list(getApplication())
    }

    fun openHistory(entry: HistoryStore.Entry) {
        val rows = HistoryStore.rows(getApplication(), entry)
        if (rows.isEmpty()) return
        val image = HistoryStore.image(getApplication(), entry)
        currentEntryId = entry.id
        _state.update {
            it.copy(
                // 原图也一起恢复：历史里同样能看原图、网格图、增强图。
                original = image,
                grid = TableGrid(rows, TableGrid.Mode.GRID_LINES),
                phase = Phase.DONE,
                stage = null,
                message = null,
                processed = null,
                overlay = null,
                enhanced = null,
            )
        }
    }

    fun deleteHistory(ids: Collection<String>) {
        HistoryStore.delete(getApplication(), ids)
        if (currentEntryId in ids) currentEntryId = null
        refreshHistory()
    }

    fun exportXlsx() {
        val grid = _state.value.grid ?: return
        runCatching {
            val names = grid.rows.flatMap { row ->
                row.filter { it.length in 2..4 && it.all { character -> character.code > 0x2E80 } }
            }
            if (names.isNotEmpty()) {
                NameMemory.remember(getApplication(), names)
                _suggestions.value = NameMemory.load(getApplication())
            }
        }
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
        currentEntryId = null
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
