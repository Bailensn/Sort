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

    @Test
    fun clearsStrayDigitsInsideEmptyColumnRun() {
        // 6 科后面连着 3 个没用到的空列，格线线头被认成 1、2、7，必须清掉。
        val rows = listOf(
            listOf("张三丰", "94.5", "", "2", ""),
            listOf("李四海", "77.5", "", "", ""),
            listOf("王五岳", "95", "1", "", "7"),
            listOf("赵六合", "87.5", "", "", ""),
            listOf("钱七曜", "81.5", "", "", ""),
        )

        val cleaned = GarbageColumnTrimmer.cleanNoisyColumns(rows)

        assertEquals("", cleaned[0][3])
        assertEquals("", cleaned[2][2])
        assertEquals("7", rows[2][4]) // 原数据不动
        assertEquals("", cleaned[2][4])
        assertEquals("94.5", cleaned[0][1])
    }

    @Test
    fun keepsDigitsInFilledColumn() {
        // 序号列每行都有值，哪怕都是单个数字也不能清。
        val rows = listOf(
            listOf("1", "张三", ""),
            listOf("2", "李四", ""),
            listOf("3", "王五", ""),
        )

        val cleaned = GarbageColumnTrimmer.cleanNoisyColumns(rows)

        assertEquals("1", cleaned[0][0])
        assertEquals("3", cleaned[2][0])
    }

    @Test
    fun keepsSingleSparseColumn() {
        // 只有一列稀疏（附加分只有两个人有），不构成「空列块」，保留。
        val rows = listOf(
            listOf("张三", "94.5", "5"),
            listOf("李四", "88", ""),
            listOf("王五", "79", ""),
            listOf("赵六", "66", ""),
            listOf("钱七", "60", ""),
            listOf("孙八", "55", ""),
        )

        val cleaned = GarbageColumnTrimmer.cleanNoisyColumns(rows)

        assertEquals("5", cleaned[0][2])
    }

    @Test
    fun clearsJunkColumnsButKeepsSparseTotal() {
        // 实测数据：6 科后面 3 个空列被认成 1/19/161/N/wa（填了不到一半的行），
        // 总分列因为有人缺考只填了 2/3。
        val rows = listOf(
            listOf("14", "25", "李四海", "77.5", "1", "1", "13", "334.5"),
            listOf("14", "26", "王五岳", "95", "19", "11", "", "411.5"),
            listOf("14", "27", "赵六合", "87.5", "161", "wa", "10", "388"),
            listOf("14", "28", "钱七曜", "81.5", "N", "161", "16", ""),
            listOf("14", "29", "孙八荒", "69", "", "", "", "301"),
            listOf("14", "30", "周九霄", "76", "", "", "", ""),
            listOf("14", "31", "吴十方", "71", "", "", "", "267.5"),
            listOf("14", "32", "冯二虎", "49.5", "", "", "", ""),
            listOf("14", "33", "郑一鸣", "80.5", "", "", "", "319"),
        )

        val cleaned = GarbageColumnTrimmer.cleanNoisyColumns(rows)

        // 三个噪声列被清空
        assertEquals("", cleaned[2][4])
        assertEquals("", cleaned[1][5])
        assertEquals("", cleaned[3][6])
        assertEquals("", cleaned[0][4])
        // 总分列只有 6/9 有值，但它是真数据，必须留着
        assertEquals("334.5", cleaned[0][7])
        assertEquals("388", cleaned[2][7])
        assertEquals("319", cleaned[8][7])
        // 姓名和分数不受影响
        assertEquals("赵六合", cleaned[2][2])
        assertEquals("87.5", cleaned[2][3])
    }
}
