package com.bailensn.sort.table

/** 一列在业务上的身份：求和校验、单科排序都靠它。 */
enum class ColumnRole {
    SEQUENCE,
    ID,
    SUBJECT,
    TOTAL,
    OTHER,
}

data class ColumnSpec(
    val index: Int,
    /** 图片里认出来的表头文字；没有表头时是空的。 */
    val name: String = "",
    val role: ColumnRole = ColumnRole.OTHER,
)

/**
 * 表格结构：每列叫什么、是什么身份。
 * fromImage 表示第一行确实是图片里的表头，已经从数据行里摘出来了。
 */
data class TableHeader(
    val columns: List<ColumnSpec> = emptyList(),
    val fromImage: Boolean = false,
) {

    val subjectColumns: List<Int>
        get() = columns.filter { it.role == ColumnRole.SUBJECT }.map { it.index }.sorted()

    val totalColumn: Int?
        get() = columns.firstOrNull { it.role == ColumnRole.TOTAL }?.index

    val hasNames: Boolean get() = columns.any { it.name.isNotBlank() }

    fun nameOf(index: Int): String = columns.firstOrNull { it.index == index }?.name.orEmpty()

    fun withName(index: Int, name: String): TableHeader = copy(
        columns = columns.map { if (it.index == index) it.copy(name = name.trim()) else it },
    )

    fun withRole(index: Int, role: ColumnRole): TableHeader = copy(
        columns = columns.map {
            when {
                it.index != index || it.role == role -> it
                // 总分只能有一个：改别的列之前先把原来的降级
                role == ColumnRole.TOTAL -> it.copy(role = ColumnRole.SUBJECT)
                else -> it.copy(role = role)
            }
        },
    )

    companion object {
        fun of(columnCount: Int): TableHeader =
            TableHeader((0 until columnCount).map { ColumnSpec(it) })
    }
}

object TableHeaderDetector {

    private val NUMBER = Regex("""^\d+(\.\d+)?$""")
    private val TOTAL_WORDS = listOf("总分", "总计", "合计", "总成绩", "总计分", "total")
    private val ID_WORDS = listOf("学号", "考号", "准考证", "考 号")
    private val SEQUENCE_WORDS = listOf("序号", "编号", "序 号")
    private val NAME_WORDS = listOf("姓名", "名字", "学生", "班级")

    fun isTotal(name: String): Boolean = TOTAL_WORDS.any { name.contains(it, ignoreCase = true) }
    fun isId(name: String): Boolean = ID_WORDS.any { name.contains(it, ignoreCase = true) }
    fun isSequence(name: String): Boolean = SEQUENCE_WORDS.any { name.contains(it, ignoreCase = true) }
    private fun isName(name: String): Boolean = NAME_WORDS.any { name.contains(it, ignoreCase = true) }

    /** 按列名判身份；叫不出身份就返回 null，交给数字特征去猜。 */
    private fun roleFromName(name: String): ColumnRole? = when {
        name.isBlank() -> null
        isTotal(name) -> ColumnRole.TOTAL
        isId(name) -> ColumnRole.ID
        isSequence(name) -> ColumnRole.SEQUENCE
        isName(name) -> ColumnRole.OTHER
        else -> null
    }

    /**
     * 认表头：第一行几乎都是文字、第二行开始出现数字，或者第一行里出现
     * 「总分」「学号」这类字眼。认不到就返回一份空名字的结构，让用户自己填。
     */
    fun detect(rows: List<List<String>>): TableHeader {
        if (rows.isEmpty()) return TableHeader()
        val width = rows.maxOfOrNull { it.size } ?: 0
        if (width == 0) return TableHeader()

        val headerRow = isHeaderRow(rows)
        val names = List(width) { column ->
            if (headerRow) rows.first().getOrNull(column)?.trim().orEmpty() else ""
        }
        val data = if (headerRow) rows.drop(1) else rows

        val columns = (0 until width).map { column ->
            val role = roleFromName(names[column]) ?: inferRole(data, column)
            ColumnSpec(column, names[column], role)
        }.toMutableList()

        // 没有表头时也尽量把总分认出来：最后一列基本等于前面各科之和就是总分
        if (!headerRow && width >= 3) {
            val last = width - 1
            val subjects = columns
                .filter { it.role == ColumnRole.SUBJECT && it.index != last }
                .map { it.index }
            if (columns[last].role == ColumnRole.SUBJECT && looksLikeTotal(data, last, subjects)) {
                columns[last] = columns[last].copy(role = ColumnRole.TOTAL)
            }
        }
        return TableHeader(columns, fromImage = headerRow)
    }

    private fun isHeaderRow(rows: List<List<String>>): Boolean {
        val first = rows.first()
        if (first.count { it.isNotBlank() } < 2) return false
        val firstNumeric = numericRatio(first)
        if (firstNumeric >= 0.34) return false
        val hinted = first.any { cell ->
            isTotal(cell) || isId(cell) || isSequence(cell) || isName(cell)
        }
        if (hinted) return true
        if (rows.size < 2) return false
        return numericRatio(rows[1]) > firstNumeric
    }

    /** 最后一列和「前面各科之和」对不对得上：对得上才敢当总分。 */
    private fun looksLikeTotal(rows: List<List<String>>, column: Int, subjects: List<Int>): Boolean {
        if (subjects.isEmpty()) return false
        var compared = 0
        var matched = 0
        rows.forEach { row ->
            val total = row.getOrNull(column)?.trim()?.toDoubleOrNull() ?: return@forEach
            val parts = subjects.mapNotNull { index -> row.getOrNull(index)?.trim()?.toDoubleOrNull() }
            if (parts.size != subjects.size) return@forEach
            compared++
            if (kotlin.math.abs(parts.sum() - total) <= 0.01) matched++
        }
        return compared >= 3 && matched.toDouble() / compared >= 0.75
    }

    private fun numericRatio(row: List<String>): Double {
        val values = row.map { it.trim() }.filter { it.isNotEmpty() }
        if (values.isEmpty()) return 0.0
        return values.count { NUMBER.matches(it) }.toDouble() / values.size
    }

    /** 没有列名时靠数据形状猜：整列数字 → 科目，长数字串 → 学号，连号 → 序号。 */
    private fun inferRole(rows: List<List<String>>, column: Int): ColumnRole {
        val values = rows.mapNotNull { row ->
            row.getOrNull(column)?.trim()?.takeIf(String::isNotEmpty)
        }
        if (values.isEmpty()) return ColumnRole.OTHER
        if (values.all { it.length >= 8 && it.all { ch -> ch in '0'..'9' } }) return ColumnRole.ID
        val numbers = values.mapNotNull { it.toDoubleOrNull() }
        if (numbers.size < rows.size * 0.6) return ColumnRole.OTHER
        val sequence = values.size >= 3 && numbers.size == values.size &&
            (1 until values.size).all { index ->
                kotlin.math.abs(numbers[index] - numbers[index - 1] - 1.0) <= 0.01
            }
        return if (sequence && values.all { it.length <= 3 }) ColumnRole.SEQUENCE else ColumnRole.SUBJECT
    }
}
