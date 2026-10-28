package com.bailensn.sort.table

/** OCR 文本框的轴对齐包围盒，单位是原图像素。 */
data class TextRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class GridLines(
    val horizontal: List<Float>,
    val vertical: List<Float>,
) {
    val isUsable: Boolean get() = horizontal.size >= 2 && vertical.size >= 2
}

data class TableGrid(
    val rows: List<List<String>>,
    val mode: Mode,
    val merges: List<TableMerge> = emptyList(),
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
