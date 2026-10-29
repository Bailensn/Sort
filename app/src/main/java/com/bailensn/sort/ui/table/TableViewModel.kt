package com.bailensn.sort.ui.table

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bailensn.sort.R
import com.bailensn.sort.data.HistoryStore
import com.bailensn.sort.data.NameMemory
import com.bailensn.sort.table.TableGrid
import com.bailensn.sort.table.TablePipeline
import com.bailensn.sort.table.TableSelfCheck
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
    /** 首次启动要解压内置模型，[message] 用于展示进度。 */
    data class Loading(val message: String? = null) : EngineState
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
        _state.update { it.copy(engine = EngineState.Loading(), message = null) }

        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                if (!OpenCVUtils.init(app)) {
                    error(app.getString(R.string.error_opencv))
                }
                val modelDir = ModelInstaller.ensure(app) { index, total ->
                    _state.update {
                        it.copy(
                            engine = EngineState.Loading(
                                app.getString(R.string.model_extracting, index + 1, total),
                            ),
                        )
                    }
                }
                _state.update { it.copy(engine = EngineState.Loading()) }
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
                    it.copy(engine = EngineState.Failed(t.message ?: app.getString(R.string.error_model_load)))
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
                    it.copy(
                        phase = Phase.ERROR,
                        message = t.message ?: app.getString(R.string.error_image_read),
                        stage = null,
                    )
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
        val app = getApplication<Application>()
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
                    app.getString(R.string.hint_no_lines, analysis.horizontalLines, analysis.verticalLines)
                } else {
                    app.getString(R.string.hint_no_text, analysis.cellCount)
                }
                _state.update {
                    it.copy(
                        phase = Phase.ERROR,
                        stage = null,
                        message = app.getString(R.string.error_table, hint),
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
                        selfCheckSummary = describeSelfCheck(analysis.selfCheck),
                        suspiciousRows = analysis.selfCheck.suspiciousRows,
                    )
                }
            } else {
                val polished = applyNameMemory(analysis.grid)
                // 三张图要缩放再压 JPEG，别放在主线程上做。
                val app = getApplication<Application>()
                val entry = withContext(Dispatchers.IO) {
                    HistoryStore.save(
                        context = app,
                        rows = polished.rows,
                        original = bitmap,
                        grid = analysis.overlay,
                        enhanced = analysis.enhanced,
                    )
                }
                currentEntryId = entry?.id
                _history.value = HistoryStore.list(app)
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
                        selfCheckSummary = describeSelfCheck(analysis.selfCheck),
                        suspiciousRows = analysis.selfCheck.suspiciousRows,
                    )
                }
            }
        } catch (t: Throwable) {
            _state.update {
                it.copy(
                    phase = Phase.ERROR,
                    stage = null,
                    message = t.message ?: app.getString(R.string.error_recognize),
                )
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
        val app = getApplication<Application>()
        viewModelScope.launch {
            // 读三张图同样走 IO，点开历史不要卡一下。
            val loaded = withContext(Dispatchers.IO) {
                LoadedHistory(
                    rows = HistoryStore.rows(app, entry),
                    original = HistoryStore.shot(app, entry, HistoryStore.Shot.ORIGINAL),
                    grid = HistoryStore.shot(app, entry, HistoryStore.Shot.GRID),
                    enhanced = HistoryStore.shot(app, entry, HistoryStore.Shot.ENHANCED),
                )
            }
            if (loaded.rows.isEmpty()) return@launch
            currentEntryId = entry.id
            _state.update {
                it.copy(
                    // 三张图一起恢复：历史里同样能切原图 / 网格 / 增强。
                    original = loaded.original,
                    overlay = loaded.grid,
                    enhanced = loaded.enhanced,
                    grid = TableGrid(loaded.rows, TableGrid.Mode.GRID_LINES),
                    phase = Phase.DONE,
                    stage = null,
                    message = null,
                    processed = null,
                )
            }
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
                _state.update { it.copy(message = getApplication<Application>().getString(R.string.error_export)) }
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

    private class LoadedHistory(
        val rows: List<List<String>>,
        val original: Bitmap?,
        val grid: Bitmap?,
        val enhanced: Bitmap?,
    )

    private fun describeSelfCheck(report: TableSelfCheck.Report): String {
        val app = getApplication<Application>()
        val parts = report.notes.map { note ->
            when (note) {
                is TableSelfCheck.Note.SequenceBreak ->
                    app.getString(R.string.selfcheck_sequence_break, note.column + 1, note.breaks)

                is TableSelfCheck.Note.SequenceOk ->
                    app.getString(R.string.selfcheck_sequence_ok, note.column + 1)

                is TableSelfCheck.Note.IdBreak ->
                    app.getString(R.string.selfcheck_id_break, note.column + 1, note.breaks)

                is TableSelfCheck.Note.SumChecked ->
                    app.getString(R.string.selfcheck_sum, note.subjects, note.totalColumn + 1)
            }
        }.toMutableList()
        if (report.correctedCells > 0) {
            parts += app.getString(R.string.selfcheck_corrected, report.correctedCells)
        }
        if (report.suspiciousRows.isNotEmpty()) {
            parts += app.getString(R.string.selfcheck_suspicious, report.suspiciousRows.size)
        }
        return if (parts.isEmpty()) {
            app.getString(R.string.selfcheck_none)
        } else {
            app.getString(R.string.selfcheck_notes, parts.joinToString(" · "))
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
