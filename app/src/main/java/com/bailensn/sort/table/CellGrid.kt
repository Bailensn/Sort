package com.bailensn.sort.table

import kotlin.math.roundToInt

/** 一个单元格在原图中的像素矩形。 */
data class TableCell(
    val row: Int,
    val column: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class CellGrid(
    val rows: Int,
    val columns: Int,
    val cells: List<TableCell>,
) {
    companion object {
        /**
         * 用检测到的横竖线切出单元格。
         * 线先按像素取整、去重，间距小于 [MIN_GAP] 的合并（防止同一条粗线被算成两条）。
         */
        fun fromLines(width: Int, height: Int, lines: GridLines): CellGrid {
            if (!lines.isUsable) return CellGrid(0, 0, emptyList())

            val rowBounds = snap(lines.horizontal, height)
            val columnBounds = snap(lines.vertical, width)
            if (rowBounds.size < 2 || columnBounds.size < 2) return CellGrid(0, 0, emptyList())

            val rows = rowBounds.size - 1
            val columns = columnBounds.size - 1
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
            return CellGrid(rows, columns, cells)
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
