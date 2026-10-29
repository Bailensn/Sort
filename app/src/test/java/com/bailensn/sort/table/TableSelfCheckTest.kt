package com.bailensn.sort.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableSelfCheckTest {

    private val clean = listOf(
        listOf("14", "1", "900000000001", "张三丰", "94.5", "7", "88", "25", "59", "56", "329.5"),
        listOf("14", "2", "900000000002", "李四海", "77.5", "24", "77", "30", "70", "56", "334.5"),
        listOf("14", "3", "900000000003", "王五岳", "95", "54", "60.5", "47", "72", "83", "411.5"),
        listOf("14", "4", "900000000004", "赵六合", "87.5", "68", "52.5", "42", "62", "76", "388"),
        listOf("14", "5", "900000000005", "钱七曜", "81.5", "44", "69.5", "48", "75", "78", "396"),
        listOf("14", "6", "900000000006", "孙八荒", "69", "59", "50", "23", "45", "55", "301"),
    )

    /** 置信度矩阵：默认全部 0.99，可指定某几格更低。 */
    private fun confidences(overrides: Map<Pair<Int, Int>, Float> = emptyMap()): List<List<Float>> =
        clean.indices.map { row ->
            clean[row].indices.map { column -> overrides[row to column] ?: 0.99f }
        }

    @Test
    fun keepsCleanTableUntouched() {
        val result = TableSelfCheck.check(clean, confidences())

        assertEquals(0, result.report.correctedCells)
        assertTrue(result.report.suspiciousRows.isEmpty())
        assertEquals(clean, result.rows)
    }

    @Test
    fun fixesSumMismatchWithSingleDigitEdit() {
        // 钱七曜的数学 44 认成 41：总和少 3。语文 81.5→84.5 也能对平，
        // 但数学那格置信度只有 0.55，理应按低置信度那一格改。
        val broken = clean.mapIndexed { index, row ->
            if (index == 4) row.toMutableList().also { it[5] = "41" } else row
        }
        val result = TableSelfCheck.check(broken, confidences(mapOf((4 to 5) to 0.55f)))

        assertEquals(1, result.report.correctedCells)
        assertEquals("44", result.rows[4][5])
        assertEquals("396", result.rows[4][10])
    }

    @Test
    fun leavesAmbiguousMismatchAlone() {
        // 同时改两格（差 3 和差 9），任何单格修改都对不平——宁可不改，只报存疑。
        val broken = clean.mapIndexed { index, row ->
            if (index == 4) row.toMutableList().also {
                it[5] = "41"
                it[6] = "60.5"
            } else row
        }
        val result = TableSelfCheck.check(broken, confidences())

        assertEquals(0, result.report.correctedCells)
        assertEquals("41", result.rows[4][5])
        assertTrue(result.report.suspiciousRows.contains(4))
    }

    @Test
    fun detectsSequenceGap() {
        // 中间少了一行：序号 1,2,3,5,6,7 —— 3→5 断了一次。
        val broken = clean.mapIndexed { index, row ->
            when (index) {
                3 -> row.toMutableList().also { it[1] = "5" }
                4 -> row.toMutableList().also { it[1] = "6" }
                5 -> row.toMutableList().also { it[1] = "7" }
                else -> row
            }
        }
        val result = TableSelfCheck.check(broken, confidences())

        assertTrue(result.report.suspiciousRows.contains(3))
        assertTrue(result.report.notes.any { it is TableSelfCheck.Note.SequenceBreak })
    }

    @Test
    fun detectsColumnThatIsStillNotNumeric() {
        // 分数列里混进一个没救回来的值 → 视为存疑行。
        val broken = clean.mapIndexed { index, row ->
            if (index == 2) row.toMutableList().also { it[4] = "95分" } else row
        }
        val result = TableSelfCheck.check(broken, confidences())

        assertTrue(result.report.suspiciousRows.contains(2))
    }

    @Test
    fun flagsNonSequentialIdNumbers() {
        val broken = clean.mapIndexed { index, row ->
            if (index == 3) row.toMutableList().also { it[2] = "900000000075" } else row
        }
        val result = TableSelfCheck.check(broken, confidences())

        assertTrue(result.report.suspiciousRows.contains(3))
        assertTrue(result.report.notes.any { it is TableSelfCheck.Note.IdBreak })
    }

    @Test
    fun recomputesTotalWhenNoSingleCellFixWorks() {
        // 总分和六科差得太多（改一位补不回来），但六科都在 → 用求和覆盖总分。
        val broken = clean.mapIndexed { index, row ->
            if (index == 4) row.toMutableList().also { it[10] = "391" } else row
        }
        val result = TableSelfCheck.check(broken, confidences(mapOf((4 to 10) to 0.5f)))

        assertEquals(1, result.report.correctedCells)
        assertEquals("396", result.rows[4][10])
    }

    @Test
    fun keepsPrintedTotalWhenConfidenceIsHigh() {
        // 总分那格很确信（0.99），即使对不上也不覆盖——可能错的是某个科目。
        val broken = clean.mapIndexed { index, row ->
            if (index == 4) row.toMutableList().also { it[10] = "391" } else row
        }
        val result = TableSelfCheck.check(broken, confidences())

        assertEquals(0, result.report.correctedCells)
        assertEquals("391", result.rows[4][10])
        assertTrue(result.report.suspiciousRows.contains(4))
    }

    @Test
    fun fillsEmptyTotalColumnFromSubjects() {
        // 总分列整列是空的（OCR 没认出来）——这正是「先找科目块、再看右边一列」要解决的场景。
        val rows = listOf(
            listOf("14", "1", "900000000001", "张三丰", "94.5", "7", "88", "25", "59", "56", ""),
            listOf("14", "2", "900000000002", "李四海", "77.5", "24", "77", "30", "70", "56", ""),
            listOf("14", "3", "900000000003", "王五岳", "95", "54", "60.5", "47", "72", "83", ""),
            listOf("14", "4", "900000000004", "赵六合", "87.5", "68", "52.5", "42", "62", "76", ""),
        )

        val result = TableSelfCheck.check(rows, confidences())

        assertEquals(4, result.report.correctedCells)
        assertEquals("329.5", result.rows[0][10])
        assertEquals("334.5", result.rows[1][10])
        assertEquals("411.5", result.rows[2][10])
        assertEquals("388", result.rows[3][10])
    }

    @Test
    fun junkColumnBeforeSelfCheckGetsDroppedNotKept() {
        // 右侧字母垃圾列必须在进自检之前就被裁掉；自检只该看到干净的 11 列。
        val rows = listOf(
            listOf("14", "1", "900000000001", "张三丰", "94.5", "7", "88", "25", "59", "56", "329.5", "abcdef"),
            listOf("14", "2", "900000000002", "李四海", "77.5", "24", "77", "30", "70", "56", "334.5", "ghijkl"),
            listOf("14", "3", "900000000003", "王五岳", "95", "54", "60.5", "47", "72", "83", "411.5", "mno"),
        )

        val trimmed = GarbageColumnTrimmer.trimAligned(rows)

        assertEquals(11, trimmed.rows.first().size)
        assertEquals("329.5", trimmed.rows[0][10])

        val result = TableSelfCheck.check(trimmed.rows, confidences())
        assertEquals("329.5", result.rows[0][10])
        assertEquals(0, result.report.correctedCells)
    }

    @Test
    fun keepsTotalLowerThanAnotherStudentsSubject() {
        // 85 分比另一个人的单科 92 还低，但它是这一行真实的总分，不能因为「看着小」就丢掉。
        val rows = listOf(
            listOf("序号", "姓名", "语文", "数学", "英语", "总分"),
            listOf("1", "示例甲", "92", "88", "90", "270"),
            listOf("2", "示例乙", "30", "25", "30", "85"),
        )

        val result = TableSelfCheck.check(rows)

        assertEquals("270", result.rows[1][5])
        assertEquals("85", result.rows[2][5])
    }

    @Test
    fun fillsBlankTotalEvenInTinyClass() {
        // 只有两个学生时，表头那格「总分」会把数字占比拉到 0.8 以下；
        // 总分列不能因此被放弃，空着的总分照样要算出来。
        val rows = listOf(
            listOf("序号", "姓名", "语文", "数学", "英语", "总分"),
            listOf("1", "示例甲", "92", "88", "90", "270"),
            listOf("2", "示例乙", "30", "25", "30", ""),
        )

        val result = TableSelfCheck.check(rows)

        assertEquals("85", result.rows[2][5])
        assertEquals("270", result.rows[1][5])
    }

    @Test
    fun distinguishesAbsentMarkFromBlankTotal() {
        // 「缺考」不是空：不要拿求和盖掉它；同列其它空着的总分照常补算。
        val rows = listOf(
            listOf("序号", "姓名", "语文", "数学", "英语", "总分"),
            listOf("1", "示例甲", "92", "88", "90", "270"),
            listOf("2", "示例乙", "30", "25", "30", "缺考"),
            listOf("3", "示例丙", "70", "60", "50", ""),
        )

        val result = TableSelfCheck.check(rows)

        assertEquals("缺考", result.rows[2][5])
        assertEquals("180", result.rows[3][5])
    }
}
