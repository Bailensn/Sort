package com.bailensn.sort.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableHeaderTest {

    @Test
    fun `认得出表头与各列身份`() {
        val rows = listOf(
            listOf("姓名", "学号", "语文", "数学", "总分"),
            listOf("张三", "900000000001", "90", "85", "175"),
            listOf("李四", "900000000002", "80", "70", "150"),
            listOf("王五", "900000000003", "70", "60", "130"),
        )

        val header = TableHeaderDetector.detect(rows)

        assertTrue(header.fromImage)
        assertEquals("学号", header.nameOf(1))
        assertEquals(ColumnRole.OTHER, header.columns[0].role)
        assertEquals(ColumnRole.ID, header.columns[1].role)
        assertEquals(listOf(2, 3), header.subjectColumns)
        assertEquals(4, header.totalColumn)
    }

    @Test
    fun `第一行就是数据时不认表头`() {
        val rows = listOf(
            listOf("张三", "900000000001", "90", "85", "175"),
            listOf("李四", "900000000002", "80", "70", "150"),
            listOf("王五", "900000000003", "70", "60", "130"),
        )

        val header = TableHeaderDetector.detect(rows)

        assertFalse(header.fromImage)
        assertTrue(header.columns.all { it.name.isEmpty() })
        assertEquals(ColumnRole.ID, header.columns[1].role)
        assertEquals(listOf(2, 3), header.subjectColumns)
    }

    @Test
    fun `总分不在最右列时按表头来算`() {
        val rows = listOf(
            listOf("张三", "90", "175", "85"),
            listOf("李四", "80", "150", "70"),
            listOf("王五", "70", "130", "60"),
        )
        val header = TableHeader(
            columns = listOf(
                ColumnSpec(0, "姓名", ColumnRole.OTHER),
                ColumnSpec(1, "语文", ColumnRole.SUBJECT),
                ColumnSpec(2, "总分", ColumnRole.TOTAL),
                ColumnSpec(3, "数学", ColumnRole.SUBJECT),
            ),
        )

        val result = TableSelfCheck.check(rows, null, header)

        val note = result.report.notes.filterIsInstance<TableSelfCheck.Note.SumChecked>().single()
        assertEquals(2, note.totalColumn)
        assertEquals(2, note.subjects)
        assertEquals("175", result.rows[0][2])
    }

    @Test
    fun `总分算错的行会被指出来`() {
        val rows = listOf(
            listOf("张三", "90", "170", "85"),
            listOf("李四", "80", "150", "70"),
            listOf("王五", "70", "130", "60"),
        )
        val header = TableHeader(
            columns = listOf(
                ColumnSpec(0, "姓名", ColumnRole.OTHER),
                ColumnSpec(1, "语文", ColumnRole.SUBJECT),
                ColumnSpec(2, "总分", ColumnRole.TOTAL),
                ColumnSpec(3, "数学", ColumnRole.SUBJECT),
            ),
        )

        val result = TableSelfCheck.check(rows, null, header)

        // 90 + 85 = 175，印出来是 170：要么被改对，要么进存疑行。
        val fixed = result.rows[0][2] == "175"
        assertTrue(fixed || result.report.suspiciousRows.contains(0))
    }
}
