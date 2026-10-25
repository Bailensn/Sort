package com.bailensn.sort.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun joinsValuesWithSeparator() {
        assertEquals("1 · 2 · 3", listOf(1, 2, 3).toDisplayString())
    }

    @Test
    fun emptyListProducesEmptyString() {
        assertEquals("", emptyList<Int>().toDisplayString())
    }
}
