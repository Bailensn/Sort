package com.bailensn.sort.table

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.model.CellReading
import com.paddle.ocr.postprocess.CellText
import kotlinx.coroutines.async
import com.paddle.ocr.model.OCRBox
import kotlin.math.max
import kotlin.math.min

/**
 * 一张图的完整处理链：
 *
 * 1. 微矫正：Hough 投影估角 → 旋转；再用外框四边做透视校正；
 * 2. 表格处理：检测横竖线 → 切单元格；
 * 3. 逐格识别：每个单元格单独裁图送识别模型，避免检测框跨列；
 * 4. 回读纠错：再跑一遍整图检测，把每个检测框按覆盖面积落回单元格，
 *    和逐格结果不一致时按置信度取舍，并统计纠正数量。
 */
object TablePipeline {

    enum class Stage { RECTIFY, SEGMENT, RECOGNIZE, VERIFY }

    data class Analysis(
        val bitmap: Bitmap,
        val overlay: Bitmap,
        val grid: TableGrid,
        val deskewAngle: Double,
        val rectified: Boolean,
        val correctedCells: Int,
        val elapsedMs: Long,
    )

    suspend fun analyze(
        ocr: PaddleOCR,
        source: Bitmap,
        onStage: (Stage) -> Unit = {},
    ): Analysis {
        val start = SystemClock.elapsedRealtime()

        onStage(Stage.RECTIFY)
        val deskewed = Deskewer.deskew(source)
        var working = deskewed.bitmap
        var detection = detectSafely(working)
        var rectified = false

        if (detection.isUsable) {
            val warped = runCatching { PerspectiveRectifier.rectify(working, detection) }.getOrNull()
            if (warped != null) {
                working = warped
                detection = detectSafely(working)
                rectified = true
            }
        }

        onStage(Stage.SEGMENT)
        // 用二值图逐条边界确认有没有画线：没画线的地方就是合并单元格。
        val probe = PixelBorderProbe.from(working)
        val cellGrid: CellGrid
        val borderInset: Int
        try {
            cellGrid = CellGrid.fromLines(working.width, working.height, detection.toGridLines(), probe)
            // 表格线宽估计 + 1px，用于裁格时把线整个切掉：
            // 残留的线头会被识别成 "1" 或 "|"，这是空白格里出现 1 的主要原因。
            borderInset = (probe?.maxThickness ?: 2) + 1
        } finally {
            probe?.release()
        }

        var grid = TableGrid(emptyList(), TableGrid.Mode.TEXT_CLUSTER)
        var correctedCells = 0

        if (cellGrid.cells.isNotEmpty()) {
            val pass = recognizeByCells(ocr, working, cellGrid, borderInset, onStage)
            grid = pass.grid
            correctedCells = pass.corrected

            if (grid.filledCellCount < 2) {
                onStage(Stage.RECOGNIZE)
                grid = recognizeByDetection(ocr, working)
                correctedCells = 0
            }
        } else {
            onStage(Stage.RECOGNIZE)
            grid = recognizeByDetection(ocr, working)
        }

        val overlay = runCatching { GridOverlay.draw(working, detection) }.getOrElse { working }
        return Analysis(
            bitmap = working,
            overlay = overlay,
            grid = grid,
            deskewAngle = deskewed.angleDegrees,
            rectified = rectified,
            correctedCells = correctedCells,
            elapsedMs = SystemClock.elapsedRealtime() - start,
        )
    }

    private data class CellPass(val grid: TableGrid, val corrected: Int)

    private suspend fun recognizeByCells(
        ocr: PaddleOCR,
        bitmap: Bitmap,
        cellGrid: CellGrid,
        borderInset: Int,
        onStage: (Stage) -> Unit,
    ): CellPass = kotlinx.coroutines.coroutineScope {
        onStage(Stage.RECOGNIZE)
        // 整图检测和逐格识别互不依赖，先并行开跑，最后再汇合。
        val detectionJob = async(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { ocr.recognize(bitmap) }.getOrNull()?.results.orEmpty()
        }
        // 按线宽往内缩，把表格线挡在识别图之外；小格子再额外缩一点。
        val rects = cellGrid.cells.map { cell ->
            val inset = maxOf(borderInset, minOf(cell.width, cell.height) / 40)
            Rect(
                (cell.left + inset).coerceAtMost(cell.right - 1),
                (cell.top + inset).coerceAtMost(cell.bottom - 1),
                (cell.right - inset).coerceAtLeast(cell.left + 1),
                (cell.bottom - inset).coerceAtLeast(cell.top + 1),
            )
        }
        val recognizedResult = runCatching { ocr.recognizeCells(bitmap, rects) }
        if (recognizedResult.isFailure) {
            return@coroutineScope CellPass(TableGrid(emptyList(), TableGrid.Mode.GRID_LINES), 0)
        }
        var recognized: List<CellReading> = recognizedResult.getOrThrow()

        // 逐格结果里置信度偏低的格子，换个尺度再认一次，取置信度高的那个：
        // 小字号、贴边的数字第一次常常认得不稳，这一遍能救回不少。
        val retryIndices = recognized.indices.filter { index ->
            val reading = recognized[index]
            reading.hasInk && reading.confidence < RETRY_CONFIDENCE
        }
        if (retryIndices.isNotEmpty()) {
            val retry = runCatching {
                ocr.recognizeCells(bitmap, retryIndices.map { rects[it] }, scale = RETRY_SCALE)
            }.getOrNull()
            if (retry != null) {
                val merged = recognized.toMutableList()
                retryIndices.forEachIndexed { position, index ->
                    val candidate = retry.getOrNull(position) ?: return@forEachIndexed
                    if (candidate.confidence > recognized[index].confidence) {
                        merged[index] = candidate
                    }
                }
                recognized = merged
            }
        }

        // 回读：整图再检测一遍，用它的结果和逐格结果互相校对。
        onStage(Stage.VERIFY)
        val detections = detectionJob.await()
        val byCell = mutableMapOf<Int, Pair<String, Float>>()

        for (result in detections) {
            val box = toRectF(result.box)
            val area = box.width() * box.height()
            if (area <= 4f) continue

            var bestIndex = -1
            var bestOverlap = 0f
            cellGrid.cells.forEachIndexed { index, cell ->
                val overlap = overlapArea(box, cell)
                if (overlap > bestOverlap) {
                    bestOverlap = overlap
                    bestIndex = index
                }
            }
            // 只有检测框几乎完整落在一个格子里才采信，跨列框（比如姓名+分数粘在一起）直接丢弃。
            if (bestIndex < 0 || bestOverlap / area < 0.75f) continue

            // 表格线在整图检测里经常被当成文字（框很细，识别成 "1" / "|"），直接丢掉。
            if (CellText.looksLikeLine(result.text)) continue

            val text = CellText.clean(result.text)
            if (text.isEmpty()) continue
            val previous = byCell[bestIndex]
            if (previous == null || result.confidence > previous.second) {
                byCell[bestIndex] = text to result.confidence
            }
        }

        var corrected = 0
        val matrix = MutableList(cellGrid.rows) { MutableList(cellGrid.columns) { "" } }

        cellGrid.cells.forEachIndexed { index, cell ->
            val reading = recognized.getOrNull(index) ?: CellReading("", 0f, hasInk = false)
            val cellText = if (reading.confidence >= 0.25f) reading.text.trim() else ""
            val detector = byCell[index]

            val chosen = when {
                // 整图检测没有落到这个格子里，以逐格结果为准。
                detector == null -> cellText

                // 格子里没有墨迹 = 真正的空白格（选科没选的科目、缺考没写标记）。
                // 这种格子在成绩单里很常见，必须保持空白：
                // 一旦用整图检测去“补”，就会把邻格或跨列框的文字填进来，整行数据错位。
                !reading.hasInk -> cellText

                // 逐格没认出来时，只有检测结果足够可信才回填，
                // 否则宁可留空：空白格被填上 1 比漏一个格子更难查。
                cellText.isEmpty() -> {
                    if (detector.second >= MIN_FILL_CONFIDENCE) {
                        corrected++
                        detector.first
                    } else {
                        ""
                    }
                }

                cellText == detector.first -> cellText

                cellText.contains(detector.first) || detector.first.contains(cellText) -> {
                    corrected++
                    if (reading.confidence >= detector.second) cellText else detector.first
                }

                reading.confidence >= detector.second -> cellText

                else -> {
                    corrected++
                    detector.first
                }
            }
            matrix[cell.row][cell.column] = chosen
        }

        // 分数列里 5→S、0→O 这类错字按列纠正：整列大多是数字时才动手。
        val repaired = NumericColumnRepair.repair(matrix)
        val grid = TableGridBuilder.normalize(repaired, TableGrid.Mode.GRID_LINES, cellGrid.merges)
            ?: TableGrid(emptyList(), TableGrid.Mode.GRID_LINES)
        CellPass(grid, corrected)
    }

    private suspend fun recognizeByDetection(ocr: PaddleOCR, bitmap: Bitmap): TableGrid {
        val run = runCatching { ocr.recognize(bitmap) }.getOrElse {
            return TableGrid(emptyList(), TableGrid.Mode.TEXT_CLUSTER)
        }
        val regions = run.results.mapNotNull { result ->
            val text = result.text.trim()
            if (text.isEmpty()) null else TableGridBuilder.regionOf(result.box, text)
        }
        return TableGridBuilder.fromClusters(regions)
            ?: TableGrid(emptyList(), TableGrid.Mode.TEXT_CLUSTER)
    }

    private const val MIN_FILL_CONFIDENCE = 0.6f

    /** 低于这个置信度的格子会换尺度重认一次。 */
    private const val RETRY_CONFIDENCE = 0.85f

    /** 重认时的放大倍率。 */
    private const val RETRY_SCALE = 1.6f

    private fun detectSafely(bitmap: Bitmap): LineDetection =
        runCatching { TableLineDetector.detect(bitmap) }.getOrElse {
            LineDetection(bitmap.width, bitmap.height, emptyList(), emptyList())
        }

    private fun toRectF(box: OCRBox): RectF {
        val xs = box.points.map { it.x }
        val ys = box.points.map { it.y }
        return RectF(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun overlapArea(box: RectF, cell: TableCell): Float {
        val left = max(box.left, cell.left.toFloat())
        val top = max(box.top, cell.top.toFloat())
        val right = min(box.right, cell.right.toFloat())
        val bottom = min(box.bottom, cell.bottom.toFloat())
        if (right <= left || bottom <= top) return 0f
        return (right - left) * (bottom - top)
    }
}
