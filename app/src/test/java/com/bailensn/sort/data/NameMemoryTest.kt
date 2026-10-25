package com.bailensn.sort.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameMemoryTest {

    @Test
    fun editDistanceCountsSingleCharacterSwap() {
        assertEquals(1, NameMemory.editDistance("张三丰", "关英树"))
        assertEquals(0, NameMemory.editDistance("李四海", "李四海"))
        assertEquals(2, NameMemory.editDistance("冯二虎", "李赫赫"))
    }

    @Test
    fun nearestFixesOneCharacterMisread() {
        val memory = listOf("张三丰", "李四海", "王五岳", "钱七曜")
        assertEquals("王五岳", NameMemory.nearest("王五岳", memory))
        assertEquals("钱七曜", NameMemory.nearest("刘木于", memory))
    }

    @Test
    fun nearestRejectsUnrelatedOrTooShort() {
        val memory = listOf("张三丰", "李四海")
        assertNull(NameMemory.nearest("冯二虎", memory))
        assertNull(NameMemory.nearest("张", memory))
        assertNull(NameMemory.nearest("完全不同的名字", memory))
    }
}
