package com.bailensn.sort.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TableGridBuilderTest {

    @Test
    fun buildsGridFromDetectedLines() {
        val regions = listOf(
            region("A1", 10f, 10f, 40f, 30f),
            region("B1", 110f, 10f, 140f, 30f),
            region("A2", 10f, 60f, 40f, 80f),
            region("B2", 110f, 60f, 140f, 80f),
        )

        val grid = TableGridBuilder.fromLines(
            regions = regions,
            lines = GridLines(
                horizontal = listOf(0f, 50f, 100f),
                vertical = listOf(0f, 100f, 200f),
            ),
        )

        assertNotNull(grid)
        assertEquals(listOf(listOf("A1", "B1"), listOf("A2", "B2")), grid!!.rows)
        assertEquals(TableGrid.Mode.GRID_LINES, grid.mode)
        assertEquals(2, grid.rowCount)
        assertEquals(2, grid.columnCount)
    }

    @Test
    fun joinsMultipleTextLinesInsideOneCell() {
        val regions = listOf(
            region("第一行", 10f, 10f, 40f, 25f),
            region("第二行", 10f, 30f, 40f, 45f),
        )

        val grid = TableGridBuilder.fromLines(
            regions = regions,
            lines = GridLines(horizontal = listOf(0f, 60f), vertical = listOf(0f, 100f)),
        )

        assertEquals(listOf(listOf("第一行\n第二行")), grid!!.rows)
    }

    @Test
    fun fallsBackToClusteringWithoutLines() {
        val regions = listOf(
            region("名称", 10f, 10f, 50f, 30f),
            region("数量", 120f, 10f, 160f, 30f),
            region("苹果", 10f, 60f, 50f, 80f),
            region("3", 120f, 60f, 160f, 80f),
        )

        val grid = TableGridBuilder.fromClusters(regions)

        assertNotNull(grid)
        assertEquals(listOf(listOf("名称", "数量"), listOf("苹果", "3")), grid!!.rows)
        assertEquals(TableGrid.Mode.TEXT_CLUSTER, grid.mode)
    }

    @Test
    fun dropsTrailingEmptyColumns() {
        val grid = TableGridBuilder.fromClusters(listOf(region("A", 10f, 10f, 30f, 30f)))

        assertEquals(listOf(listOf("A")), grid!!.rows)
        assertEquals(1, grid.columnCount)
    }

    @Test
    fun returnsNullForEmptyInput() {
        assertNull(TableGridBuilder.fromClusters(emptyList()))
        assertNull(
            TableGridBuilder.fromLines(
                regions = emptyList(),
                lines = GridLines(emptyList(), emptyList()),
            ),
        )
    }

    private fun region(text: String, left: Float, top: Float, right: Float, bottom: Float) =
        TextRegion(left = left, top = top, right = right, bottom = bottom, text = text)
}
