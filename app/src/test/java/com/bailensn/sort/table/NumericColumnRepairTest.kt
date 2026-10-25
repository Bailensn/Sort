package com.bailensn.sort.table

import org.junit.Assert.assertEquals
import org.junit.Test

class NumericColumnRepairTest {

    @Test
    fun fixesLookalikeCharactersInNumericColumn() {
        val rows = listOf(
            listOf("姓名", "语文", "数学"),
            listOf("张三", "S8", "9O.5"),
            listOf("李四", "72", "88"),
            listOf("王五", "l2", "缺考"),
            listOf("赵六", "95", "66"),
            listOf("钱七", "88", "77"),
        )

        val repaired = NumericColumnRepair.repair(rows)

        assertEquals("58", repaired[1][1])
        assertEquals("90.5", repaired[1][2])
        assertEquals("12", repaired[3][1])
        // 不是数字的格子保持原样
        assertEquals("缺考", repaired[3][2])
        // 姓名列不是数字列，不动
        assertEquals("张三", repaired[1][0])
    }

    @Test
    fun leavesTextColumnsAlone() {
        val rows = listOf(
            listOf("张三", "李四"),
            listOf("王五", "赵六"),
            listOf("孙七", "周八"),
        )

        assertEquals(rows, NumericColumnRepair.repair(rows))
    }

    @Test
    fun skipsColumnWithTooFewNumericCells() {
        val rows = listOf(
            listOf("1", "S8"),
            listOf("缺考", "缺考"),
            listOf("缺考", "缺考"),
        )

        val repaired = NumericColumnRepair.repair(rows)

        assertEquals("S8", repaired[0][1])
    }

    @Test
    fun normalizesFullWidthDigitsAndCommas() {
        val rows = listOf(
            listOf("９４，５"),
            listOf("88"),
            listOf("72"),
            listOf("66"),
        )

        assertEquals("94.5", NumericColumnRepair.repair(rows)[0][0])
    }
}
