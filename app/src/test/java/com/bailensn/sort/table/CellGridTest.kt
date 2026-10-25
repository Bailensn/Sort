package com.bailensn.sort.table

import org.junit.Assert.assertEquals
import org.junit.Test

class CellGridTest {

    @Test
    fun buildsCellsFromDetectedLines() {
        val grid = CellGrid.fromLines(
            width = 200,
            height = 100,
            lines = GridLines(
                horizontal = listOf(0f, 50f, 100f),
                vertical = listOf(0f, 100f, 200f),
            ),
        )

        assertEquals(2, grid.rows)
        assertEquals(2, grid.columns)
        assertEquals(4, grid.cells.size)

        val first = grid.cells.first()
        assertEquals(0, first.row)
        assertEquals(0, first.column)
        assertEquals(0, first.left)
        assertEquals(0, first.top)
        assertEquals(100, first.right)
        assertEquals(50, first.bottom)
    }

    @Test
    fun mergesLinesCloserThanSixPixels() {
        val grid = CellGrid.fromLines(
            width = 200,
            height = 100,
            lines = GridLines(
                horizontal = listOf(0f, 1f, 50f, 100f),
                vertical = listOf(0f, 100f, 200f),
            ),
        )

        assertEquals(2, grid.rows)
    }

    @Test
    fun returnsEmptyWhenLinesAreIncomplete() {
        val grid = CellGrid.fromLines(
            width = 200,
            height = 100,
            lines = GridLines(horizontal = listOf(0f), vertical = listOf(0f, 100f)),
        )

        assertEquals(0, grid.cells.size)
    }
}
