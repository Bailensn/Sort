package com.bailensn.sort.table

object GarbageColumnTrimmer {

    /** 填充率低于这个值（也就是四成以上是空的）才可能是噪声列。 */
    private const val MAX_NOISY_FILL = 0.6

    /** 噪声列里几乎没有长值：长于 3 字符的占比不超过两成。 */
    private const val MAX_LONG_VALUE_RATIO = 0.2

    private const val MAX_NOISE_LETTER_RATIO = 0.15

    /** 右侧列：空到这种程度就当噪声。 */
    private const val MAX_EMPTY_RATIO = 0.6

    /** 右侧列：这一比例的值是「字母连串」就当噪声。 */
    private const val JUNK_VALUE_RATIO = 0.2

    private const val LETTER_COLUMN_RATIO = 0.5

    /** 裁列结果：裁完的行 + 左边被丢掉的列数（用于对齐置信度矩阵）。 */
    data class Trimmed(val rows: List<List<String>>, val droppedLeading: Int)

    fun trim(rows: List<List<String>>): List<List<String>> = trimAligned(rows).rows

    fun trimAligned(rows: List<List<String>>): Trimmed {
        if (rows.isEmpty()) return Trimmed(rows, 0)
        val width = rows.maxOfOrNull { it.size } ?: return Trimmed(rows, 0)
        if (width <= 1) return Trimmed(rows, 0)

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
            val substantial = values.filter { it.length >= 2 }
            val letterRatio = if (substantial.isEmpty()) {
                0.0
            } else {
                substantial.count { value -> value.any { it.isLetter() } }.toDouble() / substantial.size
            }
            // 有些垃圾列填得挺满（背景文字被整列识别出来），但里面全是字母连串，
            // 这种也要丢：真数据列里不会有一半以上的值是拉丁字母。
            if (noCjk && (
                emptyRatio >= MAX_EMPTY_RATIO ||
                    junkRatio >= JUNK_VALUE_RATIO ||
                    letterRatio >= LETTER_COLUMN_RATIO
                )
            ) {
                end--
                continue
            }
            break
        }

        return Trimmed(grid.map { row -> row.subList(start, end) }, start)
    }

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
                val letterRatio = if (values.isEmpty()) {
                    0.0
                } else {
                    values.count { value -> value.any { it in 'a'..'z' || it in 'A'..'Z' } }
                        .toDouble() / values.size
                }
                // 成绩单这块业务里，拉丁字母只会是噪声：姓名是中文、学号是数字、分数是数字。
                // 所以「含字母的比例够高」可以直接判噪声，不必要求整列都没有中文
                // （横线碎片 `一` 这种会让「无中文」判定失效）。
                (values.all(::noChinese) || letterRatio >= MAX_NOISE_LETTER_RATIO) &&
                    longValues <= values.size * MAX_LONG_VALUE_RATIO
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

    private const val MAX_NON_NUMERIC_RATIO = 0.07
    private const val MIN_CHINESE_RATIO = 0.8

    fun cleanBadColumns(rows: List<List<String>>): List<List<String>> {
        if (rows.isEmpty()) return rows
        val width = rows.maxOfOrNull { it.size } ?: return rows
        if (width <= 1) return rows

        val stats = (0 until width).map { column ->
            val values = rows.mapNotNull { row -> row.getOrNull(column)?.takeIf(String::isNotBlank) }
            val filled = values.size.toDouble() / rows.size
            val numeric = values.count { NUMBER.matches(it) }
            val chinese = values.count { value -> value.any { it.code > 0x2E80 && !isLineLike(it) } }
            ColumnStats(filled, numeric, chinese, values.size)
        }
        val nameColumn = stats.indices.maxByOrNull { stats[it].chinese } ?: return rows

        val bad = BooleanArray(width) { column ->
            val stat = stats[column]
            if (column == nameColumn || stat.filled < 0.05 || stat.count == 0) return@BooleanArray false
            val nonNumericRatio = 1.0 - stat.numeric.toDouble() / stat.count
            val chineseRatio = stat.chinese.toDouble() / stat.count
            nonNumericRatio > MAX_NON_NUMERIC_RATIO && chineseRatio < MIN_CHINESE_RATIO
        }
        if (bad.none { it }) return rows

        return rows.map { row ->
            row.toMutableList().also { cells ->
                bad.forEachIndexed { column, isBad ->
                    if (isBad && column < cells.size) cells[column] = ""
                }
            }
        }
    }

    private data class ColumnStats(
        val filled: Double,
        val numeric: Int,
        val chinese: Int,
        val count: Int,
    )

    private val NUMBER = Regex("""^\d+(\.\d+)?$""")

    private fun noChinese(raw: String): Boolean {
        val value = raw.filterNot(::isLineLike)
        return value.none { it.code > 0x2E80 }
    }

    private const val LINE_LIKE = "一二三十丨|｜—–-_.·''`"

    private fun isLineLike(character: Char): Boolean = character in LINE_LIKE

    /** 长度很短、带非数字字符的碎片：`I`、`|`、`1l`。纯数字（`14`）不算，那可能是合并的班级号。 */
    private fun looksLikeStrayMark(value: String): Boolean =
        value.length <= 3 && noChinese(value) && value.any { !it.isDigit() && it != '.' && it != '-' }

    private fun looksLikeBackgroundText(value: String): Boolean =
        value.length >= 3 && noChinese(value) && value.any { it.isLetter() }
}
