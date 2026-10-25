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

    /** 总分列几乎每行都该有值。 */
    private const val MIN_TOTAL_FILL = 0.85

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
                val value = parseNumber(row.getOrNull(totalColumn)) ?: return@forEachIndexed
                if (kotlin.math.abs(sum - value) <= SUM_EPSILON) return@forEachIndexed

                val fixed = fixSingleCell(row, index, subjectColumns, totalColumn, sum, value, confidences)
                if (fixed != null) {
                    row[fixed.first] = fixed.second
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

    /** 找「总分列」和它的科目列：对得上的行数够多才认。 */
    private fun findTotalColumn(
        rows: List<List<String>>,
        columns: Int,
        numeric: List<Boolean>,
        constant: List<Boolean>,
        sequences: List<Boolean>,
        identifiers: List<Boolean>,
    ): Pair<Int, List<Int>>? {
        val fillRatio = (0 until columns).map { column ->
            rows.count { row -> row.getOrNull(column)?.isNotBlank() == true }.toDouble() / rows.size
        }
        val candidates = (0 until columns).filter { column ->
            numeric[column] && !constant[column] && !sequences[column] && !identifiers[column]
        }
        if (candidates.size < 4) return null

        var best: Pair<Int, List<Int>>? = null
        var bestAgreement = 0.0
        for (totalColumn in candidates.drop(3)) {
            if (fillRatio[totalColumn] < MIN_TOTAL_FILL) continue
            // 科目列要填得满：垃圾列往往只填了一半，混进求和会让「纠正」去改真实分数。
            val subjects = candidates.filter { it < totalColumn && fillRatio[it] >= MIN_SUBJECT_FILL }
            if (subjects.size < 2) continue
            var agree = 0
            var compared = 0
            rows.forEach { row ->
                val total = parseNumber(row.getOrNull(totalColumn)) ?: return@forEach
                val sum = subjects.sumOf { column -> parseNumber(row.getOrNull(column)).orZero() }
                if (row.getOrNull(totalColumn).isNullOrBlank()) return@forEach
                compared++
                if (kotlin.math.abs(sum - total) <= SUM_EPSILON) agree++
            }
            if (compared == 0) continue
            val agreement = agree.toDouble() / compared
            if (agreement >= MIN_SUM_AGREEMENT && agreement > bestAgreement) {
                bestAgreement = agreement
                best = totalColumn to subjects
            }
        }
        return best
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

    private fun parseNumber(raw: String?): Double? =
        raw?.trim()?.takeIf { NUMBER.matches(it) }?.toDoubleOrNull()

    private fun Double?.orZero(): Double = this ?: 0.0
}
