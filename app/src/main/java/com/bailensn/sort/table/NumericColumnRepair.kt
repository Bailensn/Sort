package com.bailensn.sort.table

/**
 * 数字列的字符级纠错。
 *
 * 成绩单里绝大多数列是分数，OCR 最典型的错误是把数字认成外形相近的字母或符号：
 * `5→S`、`0→O`、`8→B`、`1→l/I/|`、`2→Z`、`6→G`、`7→T`、`9→q`。
 * 只要一列里大部分格子都是纯数字，就用这层规则把剩下的格子按同样的字典映射回数字，
 * 映射后仍然不是数字的（比如「缺考」）保持原样。
 *
 * 纯 Kotlin，方便 JVM 单测。
 */
object NumericColumnRepair {

    private val NUMERIC = Regex("""^\d+(\.\d+)?$""")

    private val LOOKALIKES: Map<Char, Char> = mapOf(
        'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0',
        'S' to '5', 's' to '5',
        'B' to '8',
        'l' to '1', 'I' to '1', '|' to '1', 'i' to '1',
        'Z' to '2', 'z' to '2',
        'G' to '6', 'g' to '6',
        'T' to '7',
        'q' to '9',
    )

    private const val MIN_NUMERIC_CELLS = 3
    private const val MIN_NUMERIC_RATIO = 0.5

    fun repair(rows: List<List<String>>): List<List<String>> {
        if (rows.isEmpty()) return rows
        val width = rows.maxOfOrNull { it.size } ?: return rows
        val result = rows.map { it.toMutableList() }

        for (column in 0 until width) {
            val values = result.mapNotNull { row ->
                row.getOrNull(column)?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (values.size < MIN_NUMERIC_CELLS) continue

            val numeric = values.count { NUMERIC.matches(it) }
            if (numeric.toDouble() / values.size < MIN_NUMERIC_RATIO) continue

            result.forEach { row ->
                val value = row.getOrNull(column)?.trim().orEmpty()
                if (value.isEmpty() || NUMERIC.matches(value)) return@forEach
                val fixed = normalize(value) ?: return@forEach
                row[column] = fixed
            }
        }
        return result
    }

    /** 把一格里能映射成数字的字符挑出来；凑不成合法数字就返回 null。 */
    private fun normalize(raw: String): String? {
        val builder = StringBuilder(raw.length)
        for (character in raw) {
            val mapped = when {
                character in '0'..'9' -> character
                character in '０'..'９' -> '0' + (character - '０')
                character == '.' || character == ',' || character == '，' -> '.'
                else -> LOOKALIKES[character] ?: continue
            }
            builder.append(mapped)
        }

        var text = builder.toString()
        if (text.isEmpty() || text.count { it.isDigit() } == 0) return null

        // 小数点只保留第一个，其余当噪声丢掉。
        val firstDot = text.indexOf('.')
        if (firstDot >= 0) {
            text = text.substring(0, firstDot + 1) +
                text.substring(firstDot + 1).replace(".", "")
        }
        if (text.startsWith(".")) text = "0$text"
        if (text.endsWith(".")) text = text.dropLast(1)
        return if (NUMERIC.matches(text)) text else null
    }
}
