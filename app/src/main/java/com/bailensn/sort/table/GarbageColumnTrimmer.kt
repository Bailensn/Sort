package com.bailensn.sort.table

/**
 * 去掉表格右侧的垃圾列。
 *
 * 照片里常带背景（桌面、键盘、屏幕边框），线检测可能把它们当成表格的一部分，
 * 于是右侧多出几列零星字符。判定很简单：从右往左，若某列「大部分是空的」
 * 且所有非空内容都不含中文，就当成噪声丢掉；真正的数据列（分数、总分）几乎每行都有值。
 */
object GarbageColumnTrimmer {

    private const val MAX_EMPTY_RATIO = 0.6

    fun trim(rows: List<List<String>>): List<List<String>> {
        if (rows.isEmpty()) return rows
        var width = rows.maxOfOrNull { it.size } ?: return rows
        if (width <= 1) return rows

        while (width > 1) {
            val column = width - 1
            val values = rows.mapNotNull { it.getOrNull(column)?.trim()?.takeIf(String::isNotEmpty) }
            if (values.isEmpty()) {
                width--
                continue
            }
            val emptyRatio = 1.0 - values.size.toDouble() / rows.size
            val asciiOnly = values.none { value -> value.any { it.code > 0x2E80 } }
            if (emptyRatio >= MAX_EMPTY_RATIO && asciiOnly) {
                width--
                continue
            }
            break
        }
        return rows.map { row -> row.take(width) }
    }
}
