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
    fun mergesCellsWhoseBorderIsNotDrawn() {
        // 3x2 的表格线，但第一行中间那条竖线没画：标题行是跨两列的合并格。
        val probe = object : BorderProbe {
            override fun hasVerticalBorder(x: Int, top: Int, bottom: Int): Boolean {
                // x=100 是两列之间的边界；第一行（0..50）没画线。
                return !(x == 100 && top < 50)
            }

            override fun hasHorizontalBorder(y: Int, left: Int, right: Int): Boolean = true
        }

        val grid = CellGrid.fromLines(
            width = 200,
            height = 100,
            lines = GridLines(
                horizontal = listOf(0f, 50f, 100f),
                vertical = listOf(0f, 100f, 200f),
            ),
            probe = probe,
        )

        assertEquals(2, grid.rows)
        assertEquals(2, grid.columns)
        // 第一行合成一格，第二行还是两格。
        assertEquals(3, grid.cells.size)
        assertEquals(listOf(TableMerge(0, 0, 1, 2)), grid.merges)

        val title = grid.cells.first()
        assertEquals(0, title.row)
        assertEquals(0, title.column)
        assertEquals(2, title.columnSpan)
        assertEquals(200, title.right)
    }

    @Test
    fun splitsNonRectangularMergesBackIntoCells() {
        // 第一行两列没画竖线 + 右边一列没画横线，并查集会连成 L 形 —— 这种不合并。
        val probe = object : BorderProbe {
            override fun hasVerticalBorder(x: Int, top: Int, bottom: Int): Boolean {
                return !(x == 100 && top < 50)
            }

            override fun hasHorizontalBorder(y: Int, left: Int, right: Int): Boolean {
                return !(y == 50 && left >= 100)
            }
        }

        val grid = CellGrid.fromLines(
            width = 200,
            height = 100,
            lines = GridLines(
                horizontal = listOf(0f, 50f, 100f),
                vertical = listOf(0f, 100f, 200f),
            ),
            probe = probe,
        )

        assertEquals(4, grid.cells.size)
        assertEquals(emptyList<TableMerge>(), grid.merges)
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
