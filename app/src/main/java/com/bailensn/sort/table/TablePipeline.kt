package com.bailensn.sort.table

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import com.paddle.ocr.PaddleOCR
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
        val cellGrid = CellGrid.fromLines(working.width, working.height, detection.toGridLines())

        var grid = TableGrid(emptyList(), TableGrid.Mode.TEXT_CLUSTER)
        var correctedCells = 0

        if (cellGrid.cells.isNotEmpty()) {
            val pass = recognizeByCells(ocr, working, cellGrid, onStage)
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
        onStage: (Stage) -> Unit,
    ): CellPass {
        onStage(Stage.RECOGNIZE)
        val rects = cellGrid.cells.map { Rect(it.left, it.top, it.right, it.bottom) }
        val recognized = runCatching { ocr.recognizeCells(bitmap, rects) }.getOrElse {
            return CellPass(TableGrid(emptyList(), TableGrid.Mode.GRID_LINES), 0)
        }

        // 回读：整图再检测一遍，用它的结果和逐格结果互相校对。
        onStage(Stage.VERIFY)
        val detections = runCatching { ocr.recognize(bitmap) }.getOrNull()?.results.orEmpty()
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

            val text = result.text.trim()
            if (text.isEmpty()) continue
            val previous = byCell[bestIndex]
            if (previous == null || result.confidence > previous.second) {
                byCell[bestIndex] = text to result.confidence
            }
        }

        var corrected = 0
        val matrix = MutableList(cellGrid.rows) { MutableList(cellGrid.columns) { "" } }

        cellGrid.cells.forEachIndexed { index, cell ->
            val cellResult = recognized.getOrElse(index) { "" to 0f }
            val cellText = if (cellResult.second >= 0.25f) cellResult.first.trim() else ""
            val detector = byCell[index]

            val chosen = when {
                detector == null -> cellText

                cellText.isEmpty() -> {
                    corrected++
                    detector.first
                }

                cellText == detector.first -> cellText

                cellText.contains(detector.first) || detector.first.contains(cellText) -> {
                    corrected++
                    if (cellResult.second >= detector.second) cellText else detector.first
                }

                cellResult.second >= detector.second -> cellText

                else -> {
                    corrected++
                    detector.first
                }
            }
            matrix[cell.row][cell.column] = chosen
        }

        val grid = TableGridBuilder.normalize(matrix, TableGrid.Mode.GRID_LINES)
            ?: TableGrid(emptyList(), TableGrid.Mode.GRID_LINES)
        return CellPass(grid, corrected)
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
