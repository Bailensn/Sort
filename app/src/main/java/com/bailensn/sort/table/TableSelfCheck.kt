package com.bailensn.sort.table

/**
 * 表格自检：用表里本来就有的数学约束去查错、改错。
 *
 * 成绩单这类表自带三条铁律：
 *
 * 1. 序号连续递增 1；
 * 2. 学号连续递增 1（同一批学生）；
 * 3. 六科之和 = 总分。
 *
 * 只要有一条不满足，就说明那几行里有格子认错了。更关键的是：
 * 总分对不上时，如果**只改一个数字**就能对平，那这个格子几乎肯定就是错的，
 * 可以直接改回正确值——这比任何模型再认一遍都可靠，因为它是算术，不是概率。
 *
 * 纯 Kotlin，方便 JVM 单测。
 */
object TableSelfCheck {

    private val NUMBER = Regex("""^\d+(\.\d+)?$""")

    /** 总和与各科之和的允许误差（分数有一位小数）。 */
    private const val SUM_EPSILON = 0.01

    /** 判定「这一列是数字列」的最低比例。 */
    private const val NUMERIC_RATIO = 0.6

    /** 求和校验至少要这么多行对得上，才认这对「科目列 + 总列」。 */
    private const val MIN_SUM_AGREEMENT = 0.75

    /** 科目列至少要有这么多行填了值：垃圾列通常只填了一半。 */
    private const val MIN_SUBJECT_FILL = 0.75

    /** 总分列的非空值里，数字至少要占这么多——但允许它整列是空的。 */
    private const val MIN_TOTAL_NUMERIC_RATIO = 0.6

    /** 总分这格置信度低于这个值、又和求和对不上时，直接用求和的数覆盖。 */
    private const val RECOMPUTE_TOTAL_CONFIDENCE = 0.9f

    data class Report(
        /** 自检结果的一句话摘要，直接显示在结果页。 */
        val summary: String = "",
        /** 靠算术反推纠正的格子数。 */
        val correctedCells: Int = 0,
        /** 仍然对不上的行号（0 基）。 */
        val suspiciousRows: List<Int> = emptyList(),
    )

    data class Result(val rows: List<List<String>>, val report: Report)

    /**
     * @param confidences 与 rows 同形的置信度矩阵；用来在「多个格子都只改一位就能对平」时
     *                    挑认得更虚的那一格。传 null 时遇到多候选就不改。
     */
    fun check(input: List<List<String>>, confidences: List<List<Float>>? = null): Result {
        if (input.isEmpty() || input.first().isEmpty()) return Result(input, Report())
        val rows = input.map { it.toMutableList() }

        val columns = rows.first().size
        val filled = List(columns) { column ->
            rows.count { row -> row.getOrNull(column)?.isNotBlank() == true }
        }
        val numeric = List(columns) { column -> isNumericColumn(rows, column) }
        val constant = List(columns) { column -> isConstantColumn(rows, column) }
        val sequences = List(columns) { column -> isSequenceColumn(rows, column) }
        val identifiers = List(columns) { column -> isIdentifierColumn(rows, column) }

        val notes = mutableListOf<String>()
        val suspicious = mutableSetOf<Int>()

        // 1) 序号 / 学号 连续性
        sequences.forEachIndexed { column, isSequence ->
            if (!isSequence) return@forEachIndexed
            val breaks = sequenceBreaks(rows, column)
            if (breaks.isNotEmpty()) {
                suspicious.addAll(breaks)
                notes.add("第 ${column + 1} 列不连续 ${breaks.size} 处")
            } else if (filled[column] > 1) {
                notes.add("第 ${column + 1} 列连续")
            }
        }
        identifiers.forEachIndexed { column, isIdentifier ->
            if (!isIdentifier) return@forEachIndexed
            val breaks = sequenceBreaks(rows, column)
            if (breaks.isNotEmpty()) {
                suspicious.addAll(breaks)
                notes.add("第 ${column + 1} 列学号不连续 ${breaks.size} 处")
            }
        }

        // 2) 求和校验 + 单格反推
        val total = findTotalColumn(rows, columns, numeric, constant, sequences, identifiers)
        var corrected = 0
        if (total != null) {
            val (totalColumn, subjectColumns) = total
            rows.forEachIndexed { index, row ->
                val sum = subjectColumns.sumOf { column -> parseNumber(row.getOrNull(column)).orZero() }
                val allSubjectsPresent = subjectColumns.all { column ->
                    parseNumber(row.getOrNull(column)) != null
                }
                // 总分格是空的：只要六科都在，就直接算出来填上。
                val value = parseNumber(row.getOrNull(totalColumn))
                if (value == null) {
                    if (allSubjectsPresent) {
                        row[totalColumn] = formatNumber(sum)
                        corrected++
                    } else {
                        suspicious.add(index)
                    }
                    return@forEachIndexed
                }
                if (kotlin.math.abs(sum - value) <= SUM_EPSILON) return@forEachIndexed

                val fixed = fixSingleCell(row, index, subjectColumns, totalColumn, sum, value, confidences)
                if (fixed != null) {
                    row[fixed.first] = fixed.second
                    corrected++
                    return@forEachIndexed
                }
                // 单格反推不出来，但六科都在：总分是可以算出来的，用它覆盖那格。
                // 前提是总分这格本身不够有把握，否则宁可相信印出来的数字。
                val totalConfidence = confidences?.getOrNull(index)?.getOrNull(totalColumn) ?: 0f
                if (allSubjectsPresent && totalConfidence < RECOMPUTE_TOTAL_CONFIDENCE) {
                    row[totalColumn] = formatNumber(sum)
                    corrected++
                } else {
                    suspicious.add(index)
                }
            }
            notes.add("求和校验 ${subjectColumns.size} 列 → 第 ${totalColumn + 1} 列")
        }

        // 3) 数字列里仍然不是数字的格子
        numeric.forEachIndexed { column, isNumeric ->
            if (!isNumeric) return@forEachIndexed
            rows.forEachIndexed { index, row ->
                val value = row.getOrNull(column)?.trim().orEmpty()
                if (value.isNotEmpty() && !NUMBER.matches(value)) suspicious.add(index)
            }
        }

        val summary = buildString {
            if (notes.isEmpty()) append("自检：没有可用的约束")
            else append("自检：").append(notes.joinToString(" · "))
            if (corrected > 0) append(" · 已用算术纠正 $corrected 格")
            if (suspicious.isNotEmpty()) append(" · 存疑 ${suspicious.size} 行")
        }
        return Result(rows.map { it.toList() }, Report(summary, corrected, suspicious.sorted()))
    }

    /**
     * 只改一个字符就能让总和对上时，返回 (列, 新值)；否则 null。
     *
     * 有多个候选就不动——宁可不改，也不能把对的改错。
     */
    private fun fixSingleCell(
        row: MutableList<String>,
        rowIndex: Int,
        subjectColumns: List<Int>,
        totalColumn: Int,
        sum: Double,
        total: Double,
        confidences: List<List<Float>>?,
    ): Pair<Int, String>? {
        val candidates = mutableListOf<Pair<Int, String>>()
        val targets = subjectColumns + totalColumn
        for (column in targets) {
            val raw = row.getOrNull(column)?.trim().orEmpty()
            if (raw.isEmpty() || !NUMBER.matches(raw)) continue
            for (position in raw.indices) {
                val original = raw[position]
                for (digit in '0'..'9') {
                    if (digit == original) continue
                    val candidate = raw.substring(0, position) + digit + raw.substring(position + 1)
                    if (candidate[position] == original) continue
                    if (column == totalColumn) {
                        val edited = parseNumber(candidate) ?: continue
                        if (kotlin.math.abs(sum - edited) <= SUM_EPSILON) candidates.add(column to candidate)
                    } else {
                        val editedSum = sum - (parseNumber(raw) ?: 0.0) + (parseNumber(candidate) ?: 0.0)
                        if (kotlin.math.abs(editedSum - total) <= SUM_EPSILON) candidates.add(column to candidate)
                    }
                }
            }
        }
        if (candidates.size == 1) return candidates.first()
        if (candidates.isEmpty() || confidences == null) return null
        // 多个格子都能对平：改「认得更虚」的那一格；两个都虚就放弃，别赌。
        val scored = candidates.map { (column, value) ->
            val confidence = confidences.getOrNull(rowIndex)?.getOrNull(column) ?: 1f
            Triple(column, value, confidence)
        }.sortedBy { it.third }
        val best = scored.first()
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && best.third > runnerUp.third - 0.02f) return null
        return best.first to best.second
    }

    /**
     * 找「总分列 + 科目列」。
     *
     * 业务事实：**总分就是整张表最右一列**（每一行里数值最大的那个格子）。
     * 所以不去猜「哪一列像总分」，直接取最右边有数字的那一列；
     * 它右边那些空列/垃圾列先跳过，左边紧挨着的那段数字列就是科目。
     *
     * 这样也顺带解决了「总分列整列是空的」：它仍然是最后那列，等着被算出来。
     */
    private fun findTotalColumn(
        rows: List<List<String>>,
        columns: Int,
        numeric: List<Boolean>,
        constant: List<Boolean>,
        sequences: List<Boolean>,
        identifiers: List<Boolean>,
    ): Pair<Int, List<Int>>? {
        if (columns < 3) return null
        val fillRatio = (0 until columns).map { column ->
            rows.count { row -> row.getOrNull(column)?.isNotBlank() == true }.toDouble() / rows.size
        }
        val numericRatio = (0 until columns).map { column ->
            val values = rows.mapNotNull { row -> row.getOrNull(column)?.takeIf(String::isNotBlank) }
            if (values.isEmpty()) 0.0 else values.count { NUMBER.matches(it) }.toDouble() / values.size
        }
        val eligibleSubject = (0 until columns).map { column ->
            numeric[column] && !constant[column] && !sequences[column] && !identifiers[column] &&
                fillRatio[column] >= MIN_SUBJECT_FILL
        }

        // 总分 = 最右一列。两种算正常：
        //   a) 最后一列装着数字；
        //   b) 最后一列整列是空的——那是「等着被算出来」的总分列。
        // 只有最后一列是「填了东西但不是数字」的垃圾列时，才往左退到第一个像样的数字列。
        // 兜底：最后一列如果装着字母之类的垃圾（不是数字、也不是空），
        // 绝不能把它当总分——否则算出来的分会被写进垃圾列，导出后多出一列。
        val last = columns - 1
        val lastIsWritableTotal = fillRatio[last] == 0.0 ||
            numericRatio[last] >= 0.8 ||
            !hasLatinLetters(rows, last)
        val totalColumn = if (fillRatio[last] == 0.0 || numericRatio[last] >= 0.8) {
            last
        } else if (!lastIsWritableTotal) {
            var candidate = last
            while (candidate > 0 && !(fillRatio[candidate] >= 0.2 && numericRatio[candidate] >= 0.8)) {
                candidate--
            }
            candidate
        } else {
            var candidate = last
            while (candidate > 0 && !(fillRatio[candidate] >= 0.2 && numericRatio[candidate] >= 0.8)) {
                candidate--
            }
            candidate
        }
        if (totalColumn <= 0) return null

        // 科目：总分左边那段连续、填得满的数字列（中间的垃圾列跳过）。
        var end = totalColumn - 1
        while (end > 0 && !eligibleSubject[end]) end--
        if (end <= 0) return null
        var begin = end
        while (begin - 1 >= 0 && eligibleSubject[begin - 1]) begin--
        val subjects = (begin..end).toList()
        if (subjects.size < 2) return null
        return totalColumn to subjects
    }

    /** 连续列：去掉空值后严格 +1，允许中途缺行。 */
    private fun sequenceBreaks(rows: List<List<String>>, column: Int): List<Int> {
        val values = rows.mapIndexedNotNull { index, row ->
            val raw = row.getOrNull(column)?.trim().orEmpty()
            if (raw.isEmpty()) null else index to raw
        }
        if (values.size < 3) return emptyList()
        val breaks = mutableListOf<Int>()
        for (index in 1 until values.size) {
            val previous = values[index - 1].second
            val currentRow = values[index].first
            val current = values[index].second
            val previousNumber = parseNumber(previous) ?: return emptyList()
            val currentNumber = parseNumber(current) ?: return emptyList()
            if (kotlin.math.abs(currentNumber - previousNumber - 1.0) > SUM_EPSILON) {
                breaks.add(currentRow)
            }
        }
        return breaks
    }

    private fun isNumericColumn(rows: List<List<String>>, column: Int): Boolean {
        val values = rows.mapNotNull { row -> row.getOrNull(column)?.trim()?.takeIf(String::isNotEmpty) }
        if (values.isEmpty()) return false
        return values.count { NUMBER.matches(it) }.toDouble() / values.size >= NUMERIC_RATIO
    }

    private fun isConstantColumn(rows: List<List<String>>, column: Int): Boolean {
        val values = rows.mapNotNull { row -> row.getOrNull(column)?.trim()?.takeIf(String::isNotEmpty) }
        return values.size >= rows.size * 0.8 && values.distinct().size == 1
    }

    private fun isSequenceColumn(rows: List<List<String>>, column: Int): Boolean {
        val values = rows.mapNotNull { row -> row.getOrNull(column)?.trim()?.takeIf(String::isNotEmpty) }
        if (values.size < 3) return false
        if (values.any { value -> value.length > 3 || parseNumber(value) == null }) return false
        var steps = 0
        var ones = 0
        for (index in 1 until values.size) {
            val previous = parseNumber(values[index - 1]) ?: return false
            val current = parseNumber(values[index]) ?: return false
            steps++
            if (kotlin.math.abs(current - previous - 1.0) <= SUM_EPSILON) ones++
        }
        return steps > 0 && ones.toDouble() / steps >= 0.8
    }

    private fun isIdentifierColumn(rows: List<List<String>>, column: Int): Boolean {
        val values = rows.mapNotNull { row -> row.getOrNull(column)?.trim()?.takeIf(String::isNotEmpty) }
        if (values.size < rows.size * 0.7) return false
        return values.all { value -> value.length >= 8 && value.all { it in '0'..'9' } }
    }

    /** 这一列的非空值里有没有拉丁字母（垃圾列的典型特征）。 */
    private fun hasLatinLetters(rows: List<List<String>>, column: Int): Boolean =
        rows.any { row ->
            row.getOrNull(column)?.any { it in 'a'..'z' || it in 'A'..'Z' } == true
        }

    /** 329.5 → "329.5"，396.0 → "396"。 */
    private fun formatNumber(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }

    private fun parseNumber(raw: String?): Double? =
        raw?.trim()?.takeIf { NUMBER.matches(it) }?.toDoubleOrNull()

    private fun Double?.orZero(): Double = this ?: 0.0
}
