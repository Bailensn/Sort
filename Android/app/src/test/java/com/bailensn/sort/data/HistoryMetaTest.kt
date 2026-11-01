package com.bailensn.sort.data

import com.bailensn.sort.table.TableSelfCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryMetaTest {

    private val meta = HistoryMeta(
        averageConfidence = 0.9375f,
        lowConfidenceCells = 4,
        elapsedMs = 12345L,
        cellMs = 9000L,
        detectMs = 2100L,
        correctedCells = 3,
        horizontalLines = 9,
        verticalLines = 15,
        cellCount = 120,
        deskewAngle = -0.75,
        rectified = true,
        suspiciousRows = listOf(4, 7),
        notes = listOf(
            TableSelfCheck.Note.SequenceBreak(column = 2, breaks = 3),
            TableSelfCheck.Note.SequenceOk(column = 1),
            TableSelfCheck.Note.IdBreak(column = 3, breaks = 1),
            TableSelfCheck.Note.SumChecked(subjects = 6, totalColumn = 10),
            TableSelfCheck.Note.PartialTotals(rows = 2),
        ),
    )

    @Test
    fun survivesEncodeDecode() {
        val decoded = HistoryMeta.decode(meta.encode())

        assertEquals(meta, decoded)
    }

    @Test
    fun emptyMetaStillDecodes() {
        val empty = HistoryMeta()

        assertEquals(empty, HistoryMeta.decode(empty.encode()))
    }

    @Test
    fun rejectsUnknownVersion() {
        assertNull(HistoryMeta.decode("v=99\nconfidence=0.5\n"))
        assertNull(HistoryMeta.decode(""))
    }

    @Test
    fun reportCarriesSelfCheckResult() {
        val report = meta.report()

        assertEquals(3, report.correctedCells)
        assertEquals(listOf(4, 7), report.suspiciousRows)
        assertEquals(5, report.notes.size)
        assertTrue(report.notes.any { it is TableSelfCheck.Note.PartialTotals })
    }
}
