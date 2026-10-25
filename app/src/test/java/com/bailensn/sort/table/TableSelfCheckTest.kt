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
        assertTrue(result.report.summary.contains("不连续"))
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
            if (index == 3) row.toMutableList().also { it[2] = "900000000099" } else row
        }
        val result = TableSelfCheck.check(broken, confidences())

        assertTrue(result.report.suspiciousRows.contains(3))
        assertTrue(result.report.summary.contains("学号"))
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
}
