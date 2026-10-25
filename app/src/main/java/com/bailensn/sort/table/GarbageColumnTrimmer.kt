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

    /**
     * 含拉丁字母的值占比到这么高，就直接判噪声列。
     * 成绩单里姓名是中文、学号与分数是数字，字母只会来自背景或线头误识。
     */
    private const val MAX_NOISE_LETTER_RATIO = 0.15

    /** 右侧列：空到这种程度就当噪声。 */
    private const val MAX_EMPTY_RATIO = 0.6

    /** 右侧列：这一比例的值是「字母连串」就当噪声。 */
    private const val JUNK_VALUE_RATIO = 0.2

    /**
     * 右侧列：长度 ≥2 的值里，含字母的占比到这么高就丢（哪怕列填得很满）。
     * 只统计长度 ≥2 的值，是为了保住等级列（A/B/C 全是单字母，那是真数据）。
     */
    private const val LETTER_COLUMN_RATIO = 0.5

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

    /**
     * 「坏列」判定（按业务经验给的硬规则）：
     * **非数字值占比 > 7%，且含中文的值占比 < 80%** → 整列清空。
     *
     * 依据：成绩单里每一列要么是数字（班级/序号/学号/分数/总分），要么是中文（姓名）。
     * 一列里既塞不进数字、又不是中文，只可能是背景噪声或线头。
     *
     * 保护：全表中文占比最高的那一列（姓名列）永远不动——哪怕它被认花了，
     * 保留原文也比清空强。
     */
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

    /**
     * 去掉「长得像线」的字符后再判断有没有中文。
     *
     * 横线碎片最常被认成 `一`、`丨`、`|`、`—`，它们确实是汉字/符号，但语义上就是一根线。
     * 不排掉的话，整列噪声会因为一个 `一` 被当成「含中文的正常列」而保留下来。
     */
    private const val LINE_LIKE = "一二三十丨|｜—–-_.·''`"

    private fun isLineLike(character: Char): Boolean = character in LINE_LIKE

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
