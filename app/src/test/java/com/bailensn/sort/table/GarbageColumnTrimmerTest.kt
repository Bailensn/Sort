package com.bailensn.sort.table

import org.junit.Assert.assertEquals
import org.junit.Test

class GarbageColumnTrimmerTest {

    @Test
    fun dropsSparseAsciiColumnsOnTheRight() {
        // 前 4 列是真数据；后面两列是照片背景被当成列的零星字符。
        val rows = listOf(
            listOf("14", "24", "张三丰", "329.5", "83288", "00"),
            listOf("14", "25", "李四海", "334.5", "REEREEEETE", ""),
            listOf("14", "26", "王五岳", "411.5", "", ""),
            listOf("14", "27", "荘诗严", "388", "", ""),
            listOf("14", "28", "钱七曜", "396", "", ""),
        )

        val trimmed = GarbageColumnTrimmer.trim(rows)

        assertEquals(4, trimmed.first().size)
    }

    @Test
    fun keepsFullyFilledTrailingNumericColumn() {
        val rows = listOf(
            listOf("A", "1"),
            listOf("B", "2"),
            listOf("C", "3"),
        )

        assertEquals(2, GarbageColumnTrimmer.trim(rows).first().size)
    }

    @Test
    fun keepsSparseColumnWithChinese() {
        val rows = listOf(
            listOf("张三", "缺考"),
            listOf("李四", ""),
            listOf("王五", ""),
        )

        assertEquals(2, GarbageColumnTrimmer.trim(rows).first().size)
    }
}
