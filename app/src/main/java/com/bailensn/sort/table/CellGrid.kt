package com.bailensn.sort.table

import kotlin.math.roundToInt

/** 判断某条边界上是否真的有线条，用来发现合并单元格（线没画全的地方就是合并区）。 */
interface BorderProbe {
    /** 竖线 x 在 [top, bottom] 这一段是否存在。 */
    fun hasVerticalBorder(x: Int, top: Int, bottom: Int): Boolean

    /** 横线 y 在 [left, right] 这一段是否存在。 */
    fun hasHorizontalBorder(y: Int, left: Int, right: Int): Boolean
}

data class TableCell(
    val row: Int,
    val column: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val rowSpan: Int = 1,
    val columnSpan: Int = 1,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isMerged: Boolean get() = rowSpan > 1 || columnSpan > 1
}

/** 导出时写进 OOXML 的合并区域。 */
data class TableMerge(
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
)

data class CellGrid(
    val rows: Int,
    val columns: Int,
    /** 只含左上角单元格；合并区域的其余格子不在列表里。 */
    val cells: List<TableCell>,
    val merges: List<TableMerge> = emptyList(),
) {
    companion object {
        fun fromLines(
            width: Int,
            height: Int,
            lines: GridLines,
            probe: BorderProbe? = null,
        ): CellGrid {
            if (!lines.isUsable) return CellGrid(0, 0, emptyList())

            val rowBounds = snap(lines.horizontal, height)
            val columnBounds = snap(lines.vertical, width)
            if (rowBounds.size < 2 || columnBounds.size < 2) return CellGrid(0, 0, emptyList())

            val rows = rowBounds.size - 1
            val columns = columnBounds.size - 1
            val groups = if (probe == null) null else groupMerged(rows, columns, rowBounds, columnBounds, probe)

            return if (groups == null) {
                val cells = ArrayList<TableCell>(rows * columns)
                for (row in 0 until rows) {
                    for (column in 0 until columns) {
                        cells.add(
                            TableCell(
                                row = row,
                                column = column,
                                left = columnBounds[column],
                                top = rowBounds[row],
                                right = columnBounds[column + 1],
                                bottom = rowBounds[row + 1],
                            ),
                        )
                    }
                }
                CellGrid(rows, columns, cells)
            } else {
                buildGroups(rows, columns, rowBounds, columnBounds, groups)
            }
        }

        private data class GroupRect(
            val row: Int,
            val column: Int,
            val rowSpan: Int,
            val columnSpan: Int,
        )

        private fun buildGroups(
            rows: Int,
            columns: Int,
            rowBounds: List<Int>,
            columnBounds: List<Int>,
            parent: IntArray,
        ): CellGrid {
            val byRoot = LinkedHashMap<Int, MutableList<Int>>()
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    byRoot.getOrPut(find(parent, row * columns + column)) { mutableListOf() }
                        .add(row * columns + column)
                }
            }

            val rects = ArrayList<GroupRect>(byRoot.size)
            for (members in byRoot.values) {
                val minRow = members.minOf { it / columns }
                val maxRow = members.maxOf { it / columns }
                val minColumn = members.minOf { it % columns }
                val maxColumn = members.maxOf { it % columns }
                val expected = (maxRow - minRow + 1) * (maxColumn - minColumn + 1)
                if (members.size == expected) {
                    rects.add(
                        GroupRect(
                            row = minRow,
                            column = minColumn,
                            rowSpan = maxRow - minRow + 1,
                            columnSpan = maxColumn - minColumn + 1,
                        ),
                    )
                } else {
                    // 非矩形（可能只是局部漏线）：不合并，拆回普通格子更安全。
                    members.forEach { index ->
                        rects.add(GroupRect(index / columns, index % columns, 1, 1))
                    }
                }
            }
            rects.sortWith(compareBy({ it.row }, { it.column }))

            val cells = rects.map { rect ->
                TableCell(
                    row = rect.row,
                    column = rect.column,
                    left = columnBounds[rect.column],
                    top = rowBounds[rect.row],
                    right = columnBounds[rect.column + rect.columnSpan],
                    bottom = rowBounds[rect.row + rect.rowSpan],
                    rowSpan = rect.rowSpan,
                    columnSpan = rect.columnSpan,
                )
            }
            val merges = rects
                .filter { it.rowSpan > 1 || it.columnSpan > 1 }
                .map { TableMerge(it.row, it.column, it.rowSpan, it.columnSpan) }
            return CellGrid(rows, columns, cells, merges)
        }

        /** 并查集：边界上没有线就把两格并在一起。 */
        private fun groupMerged(
            rows: Int,
            columns: Int,
            rowBounds: List<Int>,
            columnBounds: List<Int>,
            probe: BorderProbe,
        ): IntArray {
            val parent = IntArray(rows * columns) { it }
            val inset = 4

            for (row in 0 until rows) {
                val top = rowBounds[row] + inset
                val bottom = rowBounds[row + 1] - inset
                if (bottom - top < 4) continue
                for (column in 0 until columns - 1) {
                    val x = columnBounds[column + 1]
                    if (!probe.hasVerticalBorder(x, top, bottom)) {
                        union(parent, row * columns + column, row * columns + column + 1)
                    }
                }
            }

            for (column in 0 until columns) {
                val left = columnBounds[column] + inset
                val right = columnBounds[column + 1] - inset
                if (right - left < 4) continue
                for (row in 0 until rows - 1) {
                    val y = rowBounds[row + 1]
                    if (!probe.hasHorizontalBorder(y, left, right)) {
                        union(parent, row * columns + column, (row + 1) * columns + column)
                    }
                }
            }
            return parent
        }

        private fun find(parent: IntArray, index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        private fun union(parent: IntArray, a: Int, b: Int) {
            val rootA = find(parent, a)
            val rootB = find(parent, b)
            if (rootA != rootB) parent[rootB] = rootA
        }

        private fun snap(values: List<Float>, limit: Int): List<Int> {
            val sorted = values.map { it.roundToInt().coerceIn(0, limit) }.sorted()
            val result = mutableListOf<Int>()
            for (value in sorted) {
                if (result.isEmpty() || value - result.last() >= MIN_GAP) {
                    result.add(value)
                }
            }
            return result
        }

        private const val MIN_GAP = 6
    }
}
