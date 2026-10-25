package com.bailensn.sort.table

import com.paddle.ocr.model.OCRBox

/**
 * 把 OCR 文本框还原成二维表格。
 *
 * 两条路径：
 * 1. [fromLines]：优先用 OpenCV 检测到的横竖线切格，合并单元格会被拆开，但整体最准；
 * 2. [fromClusters]：检测不到线时，按行聚类 + 列方向的空白间隔推断。
 *
 * 这里不依赖 Android/OpenCV，纯几何计算，方便 JVM 单测。
 */
object TableGridBuilder {

    fun regionOf(box: OCRBox, text: String): TextRegion {
        val xs = box.points.map { it.x }
        val ys = box.points.map { it.y }
        return TextRegion(
            left = xs.min(),
            top = ys.min(),
            right = xs.max(),
            bottom = ys.max(),
            text = text,
        )
    }

    fun fromLines(regions: List<TextRegion>, lines: GridLines): TableGrid? {
        if (!lines.isUsable || regions.isEmpty()) return null

        val rowBounds = lines.horizontal.sorted()
        val columnBounds = lines.vertical.sorted()
        val rowCount = rowBounds.size - 1
        val columnCount = columnBounds.size - 1
        if (rowCount <= 0 || columnCount <= 0) return null

        val buckets = List(rowCount) { MutableList(columnCount) { mutableListOf<TextRegion>() } }
        for (region in regions) {
            val row = rowBounds.indexOfLast { it <= region.centerY }
            val column = columnBounds.indexOfLast { it <= region.centerX }
            if (row !in 0 until rowCount || column !in 0 until columnCount) continue
            buckets[row][column].add(region)
        }

        val cells = buckets.map { row -> row.map { joinCell(it) } }
        return normalize(cells, TableGrid.Mode.GRID_LINES)
    }

    fun fromClusters(regions: List<TextRegion>): TableGrid? {
        if (regions.isEmpty()) return null

        val medianHeight = regions.map { it.height }.sorted().let { it[it.size / 2] }
        val rowGap = (medianHeight * 0.6f).coerceAtLeast(4f)

        val rowGroups = mutableListOf<MutableList<TextRegion>>()
        for (region in regions.sortedBy { it.centerY }) {
            val current = rowGroups.lastOrNull()
            val currentCenter = current?.let { group ->
                group.sumOf { it.centerY.toDouble() }.toFloat() / group.size
            }
            if (current == null || currentCenter == null || region.centerY - currentCenter > rowGap) {
                rowGroups.add(mutableListOf(region))
            } else {
                current.add(region)
            }
        }

        val tableLeft = regions.minOf { it.left }
        val tableRight = regions.maxOf { it.right }
        val tableWidth = (tableRight - tableLeft).coerceAtLeast(1f)
        val minGap = (tableWidth * 0.01f).coerceAtLeast(8f)

        // 特别宽的行（标题、说明）会把所有列“桥接”成一列，算列时先排除。
        val columnSamples = regions.filter { it.width < tableWidth * 0.5f }.ifEmpty { regions }
        val columns = columnRuns(columnSamples, tableLeft, tableWidth, minGap)
        if (columns.isEmpty()) return null

        val columnStarts = columns.map { it.first + tableLeft }

        val cells = rowGroups.map { group ->
            val row = MutableList(columns.size) { "" }
            group.sortedBy { it.left }.forEach { region ->
                val index = columnStarts.indexOfLast { it <= region.centerX }
                if (index in row.indices) {
                    row[index] = if (row[index].isBlank()) region.text.trim() else "${row[index]}\n${region.text.trim()}"
                }
            }
            row
        }

        return normalize(cells, TableGrid.Mode.TEXT_CLUSTER)
    }

    private fun joinCell(regions: List<TextRegion>): String =
        regions
            .sortedWith(compareBy({ it.top }, { it.left }))
            .joinToString("\n") { it.text.trim() }
            .trim()

    /**
     * 把 x 方向上有文字覆盖的区间合并成列，间隔小于 [minGap] 的视为同一列内部的字距。
     * 返回值是相对 [tableLeft] 的区间。
     */
    private fun columnRuns(
        regions: List<TextRegion>,
        tableLeft: Float,
        tableWidth: Float,
        minGap: Float,
    ): List<IntRange> {
        val bins = tableWidth.toInt().coerceAtLeast(1) + 1
        val covered = BooleanArray(bins)
        for (region in regions) {
            val start = (region.left - tableLeft).toInt().coerceIn(0, bins - 1)
            val end = (region.right - tableLeft).toInt().coerceIn(0, bins - 1)
            for (index in start..end) covered[index] = true
        }

        val runs = mutableListOf<IntRange>()
        var start = -1
        for (index in 0..bins) {
            val hit = index < bins && covered[index]
            if (hit && start < 0) start = index
            if (!hit && start >= 0) {
                runs.add(start until index)
                start = -1
            }
        }
        if (runs.isEmpty()) return emptyList()

        val merged = mutableListOf<IntRange>()
        var current = runs.first()
        for (run in runs.drop(1)) {
            if (run.first - current.last <= minGap) {
                current = current.first..run.last
            } else {
                merged.add(current)
                current = run
            }
        }
        merged.add(current)
        return merged
    }

    /**
     * 去掉首尾整行为空、右侧整列为空的行列，并补齐每行长度。
     * [merges] 会跟着行列裁剪一起平移 / 截断，保证合并区域仍指向正确的格子。
     */
    internal fun normalize(
        cells: List<List<String>>,
        mode: TableGrid.Mode,
        merges: List<TableMerge> = emptyList(),
    ): TableGrid? {
        if (cells.isEmpty()) return null
        val width = cells.maxOf { it.size }
        if (width == 0) return null

        val padded = cells.map { row -> List(width) { index -> row.getOrElse(index) { "" } } }
        val leading = padded.indexOfFirst { row -> row.any { it.isNotBlank() } }
        if (leading < 0) return null

        val trimmedRows = padded
            .drop(leading)
            .dropLastWhile { row -> row.all { it.isBlank() } }
        if (trimmedRows.isEmpty()) return null

        var lastColumn = width - 1
        while (lastColumn >= 0 && trimmedRows.all { it[lastColumn].isBlank() }) {
            lastColumn--
        }
        if (lastColumn < 0) return null

        val shiftedMerges = merges.mapNotNull { merge ->
            val row = merge.row - leading
            if (row < 0 || row >= trimmedRows.size || merge.column > lastColumn) {
                null
            } else {
                val columnSpan = merge.columnSpan.coerceAtMost(lastColumn - merge.column + 1)
                if (columnSpan <= 0) null else TableMerge(row, merge.column, merge.rowSpan, columnSpan)
            }
        }

        return TableGrid(
            rows = trimmedRows.map { it.take(lastColumn + 1) },
            mode = mode,
            merges = shiftedMerges,
        )
    }
}
