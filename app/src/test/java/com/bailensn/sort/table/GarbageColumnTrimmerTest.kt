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
    fun dropsLeadingColumnWithOnlyAStrayCharacter() {
        // 照片左边那道线头会切出一列几乎全空的白格，唯一的字符是线被认成了 "I"。
        val rows = listOf(
            listOf("I", "14", "24"),
            listOf("", "14", "25"),
            listOf("", "14", "26"),
        )

        val trimmed = GarbageColumnTrimmer.trim(rows)

        assertEquals(2, trimmed.first().size)
        assertEquals(listOf("14", "24"), trimmed.first())
    }

    @Test
    fun dropsTrailingColumnMixingDigitsAndLatinJunk() {
        // 右侧背景列：数字和字母连串混在一起，整列都要丢掉。
        val rows = listOf(
            listOf("14", "24", "张三丰", "329.5", "83288"),
            listOf("14", "25", "李四海", "334.5", "REEREEEETE"),
            listOf("14", "26", "王五岳", "411.5", "2"),
            listOf("14", "27", "赵六合", "388", "1SGER0N"),
            listOf("14", "28", "钱七曜", "396", "2502"),
        )

        val trimmed = GarbageColumnTrimmer.trim(rows)

        assertEquals(4, trimmed.first().size)
        assertEquals("329.5", trimmed.first().last())
    }

    @Test
    fun keepsTrailingGradeLetters() {
        // 等级列每行一个单字母，是真实数据，不能当噪声裁掉。
        val rows = listOf(
            listOf("张三", "A"),
            listOf("李四", "B"),
            listOf("王五", "C"),
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
