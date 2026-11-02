package com.bailensn.sort.table

data class TableGrid(
    val rows: List<List<String>>,
    val mode: Mode,
) {
    enum class Mode {
        /** 靠检测到的横竖线切格。 */
        GRID_LINES,

        /** 没有线，按文字间距聚类推断。 */
        TEXT_CLUSTER,
    }

    val rowCount: Int get() = rows.size
    val columnCount: Int get() = rows.maxOfOrNull { it.size } ?: 0
    val filledCellCount: Int get() = rows.sumOf { row -> row.count { it.isNotBlank() } }
    val isEmpty: Boolean get() = rows.isEmpty() || columnCount == 0
}
