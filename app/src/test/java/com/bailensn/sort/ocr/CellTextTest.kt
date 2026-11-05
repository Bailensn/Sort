package com.bailensn.sort.ocr

import com.paddle.ocr.postprocess.CellText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CellTextTest {

    @Test
    fun dropsLineRemnantsAroundCellText() {
        assertEquals("张三", CellText.clean("| 张三 |"))
        assertEquals("92", CellText.clean("_ 92 _"))
        assertEquals("缺考", CellText.clean("—缺考—"))
    }

    @Test
    fun returnsEmptyWhenOnlyNoise() {
        assertEquals("", CellText.clean("|"))
        assertEquals("", CellText.clean("—"))
        assertEquals("", CellText.clean("  "))
    }

    @Test
    fun removesSpacesBetweenChineseCharactersButKeepsEnglish() {
        assertEquals("张三", CellText.clean("张 三"))
        assertEquals("A B", CellText.clean("A B"))
        assertEquals("1200", CellText.clean("1200"))
    }

    @Test
    fun detectsLineLikeOutputs() {
        assertTrue(CellText.looksLikeLine("1"))
        assertTrue(CellText.looksLikeLine("|"))
        assertTrue(CellText.looksLikeLine("lI"))
        assertFalse(CellText.looksLikeLine("12"))
        assertFalse(CellText.looksLikeLine("缺考"))
        assertFalse(CellText.looksLikeLine(""))
    }
}
