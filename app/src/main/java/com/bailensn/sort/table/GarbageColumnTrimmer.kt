package com.bailensn.sort.table

/**
 * 裁掉表格两侧的垃圾列。
 *
 * 照片里常带背景（桌面、黑边、屏幕边框），线检测把这些区域也切成列，
 * 于是表格右边会多出几列零星字符。这些列有两个特征：
 *
 * - 大多很空（真数据列几乎每行都有值）；
 * - 出现拉丁字母连串（`REEREEEETE`、`1SGER0N`、`EbrY`），表格数据里不会有这种东西。
 *
 * 数字连串（`83288`、`2502`）本身看不出来，但它和字母连串挤在同一列，
 * 整列一起丢掉即可。左侧同理：照片边缘的线头会多出一列几乎全空的白格。
 */
object GarbageColumnTrimmer {

    /** 填充率低于这个值（也就是四成以上是空的）才可能是噪声列。 */
    private const val MAX_NOISY_FILL = 0.6

    /** 噪声列里几乎没有长值：长于 3 字符的占比不超过两成。 */
    private const val MAX_LONG_VALUE_RATIO = 0.2

    /** 右侧列：空到这种程度就当噪声。 */
    private const val MAX_EMPTY_RATIO = 0.6

    /** 右侧列：这一比例的值是「字母连串」就当噪声。 */
    private const val JUNK_VALUE_RATIO = 0.2

    fun trim(rows: List<List<String>>): List<List<String>> {
        if (rows.isEmpty()) return rows
        val width = rows.maxOfOrNull { it.size } ?: return rows
        if (width <= 1) return rows

        val grid = rows.map { row -> List(width) { index -> row.getOrNull(index).orEmpty().trim() } }

        var start = 0
        while (start < width - 1) {
            val values = grid.mapNotNull { it[start].takeIf(String::isNotEmpty) }
            // 整列没字 → 照片边缘的白格。
            if (values.isEmpty()) {
                start++
                continue
            }
            // 整列只有一个碎字符（线头被认成 "I"、"|"）→ 也裁掉。
            // 但合并单元格的列本来就只有一个值（比如整列都是同一个班级号），那是真数据。
            if (values.size == 1 && looksLikeStrayMark(values[0])) {
                start++
                continue
            }
            break
        }

        var end = width
        while (end - start > 1) {
            val column = end - 1
            val values = grid.mapNotNull { it[column].takeIf(String::isNotEmpty) }
            if (values.isEmpty()) {
                end--
                continue
            }
            val emptyRatio = 1.0 - values.size.toDouble() / grid.size
            val noCjk = values.all(::noChinese)
            val junkRatio = values.count(::looksLikeBackgroundText).toDouble() / values.size
            if (noCjk && (emptyRatio >= MAX_EMPTY_RATIO || junkRatio >= JUNK_VALUE_RATIO)) {
                end--
                continue
            }
            break
        }

        return grid.map { row -> row.subList(start, end) }
    }

    /**
     * 清掉「空列里的噪声值」。
     *
     * 成绩单里常有整块没用到的空列（选科、备注），但线头、格线、背景会在里面被认成
     * `1`、`161`、`N`、`wa` 这种短噪声。它们混在真数据里，排序时就变成了脏数据。
     *
     * 判定一个列是噪声列：**空得多**（>40% 空）、**没有中文**、**几乎没有长于 3 字符的值**
     * （真数据里的班级、学号、总分都更长）。且必须**相邻两列以上**都这样才算——
     * 单个稀疏列（比如只有少数人有成绩的附加分）保留。
     */
    fun cleanNoisyColumns(rows: List<List<String>>): List<List<String>> {
        if (rows.isEmpty()) return rows
        val width = rows.maxOfOrNull { it.size } ?: return rows
        if (width <= 1) return rows

        val noisy = BooleanArray(width) { column ->
            val values = rows.mapNotNull { row -> row.getOrNull(column)?.takeIf(String::isNotBlank) }
            val filledRatio = values.size.toDouble() / rows.size
            if (filledRatio > MAX_NOISY_FILL) {
                false
            } else {
                val longValues = values.count { it.length > 3 }
                values.all(::noChinese) && longValues <= values.size * MAX_LONG_VALUE_RATIO
            }
        }

        var start = 0
        while (start < width) {
            if (!noisy[start]) {
                start++
                continue
            }
            var end = start
            while (end + 1 < width && noisy[end + 1]) end++
            // 单列不算：真数据里「只有部分行有值」的列很常见。
            if (end == start) noisy[start] = false
            start = end + 1
        }
        if (noisy.none { it }) return rows

        return rows.map { row ->
            row.toMutableList().also { cells ->
                noisy.forEachIndexed { column, isNoisy ->
                    if (isNoisy && column < cells.size) cells[column] = ""
                }
            }
        }
    }

    private fun noChinese(value: String): Boolean = value.none { it.code > 0x2E80 }

    /** 长度很短、带非数字字符的碎片：`I`、`|`、`1l`。纯数字（`14`）不算，那可能是合并的班级号。 */
    private fun looksLikeStrayMark(value: String): Boolean =
        value.length <= 3 && noChinese(value) && value.any { !it.isDigit() && it != '.' && it != '-' }

    /**
     * 拉丁字母连串，长度 3 以上：`RRN`、`V68`、`PSON`、`1SGER0N`。
     * 单个字母（等级 A/B/C）不算，那是真实数据。
     */
    private fun looksLikeBackgroundText(value: String): Boolean =
        value.length >= 3 && noChinese(value) && value.any { it.isLetter() }
}
