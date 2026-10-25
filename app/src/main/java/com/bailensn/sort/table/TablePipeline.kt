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
        /** 诊断用：检测到的横线条数。 */
        val horizontalLines: Int,
        /** 诊断用：检测到的竖线条数。 */
        val verticalLines: Int,
        /** 诊断用：线切出来的单元格数。 */
        val cellCount: Int,
        /** 逐格识别耗时（含低置信度重认）。 */
        val cellMs: Long = 0L,
        /** 整图检测耗时（和逐格识别并行跑）。 */
        val detectMs: Long = 0L,
        /** 增强后的灰度图：二遍识别的输入，也拿来给用户看。 */
        val enhanced: Bitmap? = null,
    ) {
        /** 除识别以外的耗时：纠偏、线检测、切格、画网格。 */
        val structureMs: Long get() = (elapsedMs - cellMs).coerceAtLeast(0L)
    }

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
            // 个别照片外框求交会算出坏四边形，warp 出来一条白带；这种就当没校正过。
            // 判据：warp 结果不能白、不能丢内容、而且上面还得能重新检到表格线。
            val warpedDetection = warped?.let { detectSafely(it) }
            val usable = warped != null &&
                warpedDetection != null &&
                !looksBlank(warped) &&
                keepsInk(working, warped) &&
                warpedDetection.isUsable
            if (usable) {
                working = warped!!
                detection = warpedDetection!!
                rectified = true
            } else {
                warped?.recycle()
            }
        }

        // 二遍识别和界面预览都用这张：照度压平 + 拉对比度后的灰度图。
        val enhanced = runCatching { ImageEnhancer.enhance(working) }.getOrNull()

        onStage(Stage.SEGMENT)
        // 用二值图逐条边界确认有没有画线：没画线的地方就是合并单元格。
        val probe = PixelBorderProbe.from(working)
        var cellCount = 0
        val cellGrid: CellGrid
        val borderInset: Int
        try {
            cellGrid = CellGrid.fromLines(working.width, working.height, detection.toGridLines(), probe)
            // 表格线宽估计 + 1px，用于裁格时把线整个切掉：
            // 残留的线头会被识别成 "1" 或 "|"，这是空白格里出现 1 的主要原因。
            borderInset = (probe?.maxThickness ?: 2) + 1
            cellCount = cellGrid.cells.size
        } finally {
            probe?.release()
        }

        var grid = TableGrid(emptyList(), TableGrid.Mode.TEXT_CLUSTER)
        var correctedCells = 0
        var cellMs = 0L
        var detectMs = 0L

        if (cellGrid.cells.isNotEmpty()) {
            val pass = recognizeByCells(ocr, working, enhanced, cellGrid, borderInset, onStage)
            grid = pass.grid
            correctedCells = pass.corrected
            cellMs = pass.cellMs
            detectMs = pass.detectMs

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
            horizontalLines = detection.horizontal.size,
            verticalLines = detection.vertical.size,
            cellCount = cellCount,
            cellMs = cellMs,
            detectMs = detectMs,
            enhanced = enhanced,
        )
    }

    private data class CellPass(
        val grid: TableGrid,
        val corrected: Int,
        val cellMs: Long = 0L,
        val detectMs: Long = 0L,
    )

    private suspend fun recognizeByCells(
        ocr: PaddleOCR,
        bitmap: Bitmap,
        enhanced: Bitmap?,
        cellGrid: CellGrid,
        borderInset: Int,
        onStage: (Stage) -> Unit,
    ): CellPass = kotlinx.coroutines.coroutineScope {
        onStage(Stage.RECOGNIZE)
        // 整图检测和逐格识别互不依赖，先并行开跑，最后再汇合。
        val detectionJob = async(kotlinx.coroutines.Dispatchers.Default) {
            val started = SystemClock.elapsedRealtime()
            val results = runCatching { ocr.recognize(bitmap) }.getOrNull()?.results.orEmpty()
            results to (SystemClock.elapsedRealtime() - started)
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
        val cellStarted = SystemClock.elapsedRealtime()
        val recognizedResult = runCatching { ocr.recognizeCells(bitmap, rects) }
        if (recognizedResult.isFailure) {
            return@coroutineScope CellPass(TableGrid(emptyList(), TableGrid.Mode.GRID_LINES), 0)
        }
        var recognized: List<CellReading> = recognizedResult.getOrThrow()

        // 第二遍：最严厉的那一遍。
        // 第一遍是原图、原尺寸的「初步识别」；凡是有墨迹但置信度没到 0.9 的格子，
        // 一律换增强图（压平光照 + CLAHE）再放大 2 倍重认，两边取更可信的那个。
        // 两遍都认成同一串字时给一点置信度加成——独立两次都一致，基本就是对的。
        val strictIndices = recognized.indices.filter { index ->
            val reading = recognized[index]
            reading.hasInk && reading.confidence < STRICT_CONFIDENCE
        }
        if (strictIndices.isNotEmpty()) {
            val strictSource = enhanced ?: bitmap
            val strict = runCatching {
                ocr.recognizeCells(strictSource, strictIndices.map { rects[it] }, scale = STRICT_SCALE)
            }.getOrNull()
            if (strict != null) {
                val merged = recognized.toMutableList()
                strictIndices.forEachIndexed { position, index ->
                    val candidate = strict.getOrNull(position) ?: return@forEachIndexed
                    val current = recognized[index]
                    merged[index] = when {
                        candidate.text.isNotBlank() && candidate.text == current.text ->
                            CellReading(current.text, minOf(1f, maxOf(current.confidence, candidate.confidence) + AGREE_BONUS), true)

                        candidate.confidence > current.confidence -> candidate
                        else -> current
                    }
                }
                recognized = merged
            }
        }

        // 第三遍：极限精度。二遍之后还不到 0.9 的格子，用 3 倍放大各认一次增强图和原图，
        // 连同前两遍的结果一起投票：两次认成同一串字的优先，其次比置信度。
        // 注意 rec 会把裁图统一缩到 48 高，放大只是改善重采样，推理开销几乎不变。
        val thirdIndices = recognized.indices.filter { index ->
            val reading = recognized[index]
            reading.hasInk && reading.confidence < THIRD_CONFIDENCE
        }
        if (thirdIndices.isNotEmpty()) {
            val rects3 = thirdIndices.map { rects[it] }
            val fromEnhanced = runCatching {
                ocr.recognizeCells(enhanced ?: bitmap, rects3, scale = THIRD_SCALE)
            }.getOrNull()
            val fromOriginal = runCatching {
                ocr.recognizeCells(bitmap, rects3, scale = THIRD_SCALE)
            }.getOrNull()
            // 复核模型（PP-OCRv5 server rec）：更大更准，只在这批难认的格子上投一票。
            val fromReferee = runCatching {
                ocr.recognizeCellsReferee(enhanced ?: bitmap, rects3, scale = REFEREE_SCALE)
            }.getOrNull()?.takeIf { it.size == rects3.size }
            if (fromEnhanced != null || fromOriginal != null || fromReferee != null) {
                val merged = recognized.toMutableList()
                thirdIndices.forEachIndexed { position, index ->
                    val candidates = listOfNotNull(
                        recognized[index],
                        fromEnhanced?.getOrNull(position),
                        fromOriginal?.getOrNull(position),
                        fromReferee?.getOrNull(position),
                    )
                    merged[index] = pickBest(candidates)
                }
                recognized = merged
            }
        }

        val cellMs = SystemClock.elapsedRealtime() - cellStarted

        // 回读：整图再检测一遍，用它的结果和逐格结果互相校对。
        onStage(Stage.VERIFY)
        val (detections, detectMs) = detectionJob.await()
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

        // 空列里冒出来的单个数字是格线线头，先清掉；再裁两侧被背景带出来的垃圾列。
        val trimmed = GarbageColumnTrimmer.trim(GarbageColumnTrimmer.cleanIsolatedDigits(matrix))
        // 分数列里 5→S、0→O 这类错字按列纠正：整列大多是数字时才动手。
        val repaired = NumericColumnRepair.repair(trimmed)
        val grid = TableGridBuilder.normalize(repaired, TableGrid.Mode.GRID_LINES, cellGrid.merges)
            ?: TableGrid(emptyList(), TableGrid.Mode.GRID_LINES)
        CellPass(grid, corrected, cellMs, detectMs)
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

    /**
     * 校正后不能把内容丢了。
     *
     * 透视变换把表格拉正时，墨迹总量基本不变；如果结果里的暗像素只剩一点点，
     * 说明四边形选歪了、只框到表格的一条边——这种白带状的图继续往下走没用。
     */
    private fun keepsInk(source: Bitmap, warped: Bitmap): Boolean {
        val before = darkRatio(source)
        if (before <= 0.0) return true
        return darkRatio(warped) >= before * 0.5
    }

    /** 抽 96x96 采样估暗像素占比。 */
    private fun darkRatio(bitmap: Bitmap): Double = runCatching {
        val size = 96
        val small = Bitmap.createScaledBitmap(bitmap, size, size, false)
        val pixels = IntArray(size * size)
        small.getPixels(pixels, 0, size, 0, 0, size, size)
        if (small !== bitmap) small.recycle()
        var dark = 0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if ((r * 299 + g * 587 + b * 114) / 1000 < 160) dark++
        }
        dark.toDouble() / pixels.size
    }.getOrDefault(0.0)

    /** 抽 64x64 采样，估算「几乎全白」的图。 */
    private fun looksBlank(bitmap: Bitmap): Boolean {
        return runCatching {
            val size = 64
            val small = Bitmap.createScaledBitmap(bitmap, size, size, false)
            val pixels = IntArray(size * size)
            small.getPixels(pixels, 0, size, 0, 0, size, size)
            if (small !== bitmap) small.recycle()
            var dark = 0
            for (pixel in pixels) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if ((r * 299 + g * 587 + b * 114) / 1000 < 160) dark++
            }
            dark.toDouble() / pixels.size < 0.002
        }.getOrDefault(false)
    }

    private const val MIN_FILL_CONFIDENCE = 0.6f

    /** 低于这个置信度就进第二遍（最严厉的那遍）。 */
    private const val STRICT_CONFIDENCE = 0.9f

    /** 第二遍的放大倍率：小字号、贴边的数字靠这一步救回来。 */
    private const val STRICT_SCALE = 2.0f

    /** 两遍认出来的字一致时，给置信度的加成。 */
    private const val AGREE_BONUS = 0.05f

    /** 第三遍的门槛：二遍之后还低于它就再极限认一次。 */
    private const val THIRD_CONFIDENCE = 0.9f

    /** 第三遍的放大倍率。 */
    private const val THIRD_SCALE = 3.0f

    /** 复核模型的放大倍率：server 模型本身容量大，2 倍就够。 */
    private const val REFEREE_SCALE = 2.0f

    /** 三遍投票时，「多一遍认成同样的字」值多少置信度。 */
    private const val VOTE_BONUS = 0.12f

    /**
     * 多遍结果里挑一个：同文本出现次数多的优先，其次看置信度。
     * 空文本永远排最后——宁可空着，也别把噪声填进格子。
     */
    private fun pickBest(candidates: List<CellReading>): CellReading {
        if (candidates.isEmpty()) return CellReading("", 0f, hasInk = false)
        if (candidates.size == 1) return candidates[0]
        val grouped = candidates
            .filter { it.text.isNotBlank() }
            .groupBy { it.text }
            .mapValues { (_, group) -> group.maxByOrNull { it.confidence }!! }
        if (grouped.isEmpty()) return candidates.maxByOrNull { it.confidence }!!
        return grouped.maxByOrNull { (text, reading) ->
            val votes = candidates.count { it.text == text }
            reading.confidence + VOTE_BONUS * (votes - 1)
        }!!.value
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
