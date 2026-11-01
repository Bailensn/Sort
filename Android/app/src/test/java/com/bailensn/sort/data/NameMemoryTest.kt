package com.bailensn.sort.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 注意：这里所有姓名都是**虚构占位名**（张三丰/李四海/王五岳/钱七曜）。
 * 任何真实人名都不允许出现在本仓库，包括测试数据、注释和示例。
 */
class NameMemoryTest {

    @Test
    fun editDistanceCountsSingleCharacterSwap() {
        assertEquals(1, NameMemory.editDistance("张三丰", "张三风"))
        assertEquals(0, NameMemory.editDistance("李四海", "李四海"))
        assertEquals(1, NameMemory.editDistance("钱七曜", "钱七耀"))
    }

    @Test
    fun nearestFixesOneCharacterMisread() {
        val memory = listOf("张三丰", "李四海", "王五岳", "钱七曜")
        assertEquals("张三丰", NameMemory.nearest("张三风", memory))
        assertEquals("王五岳", NameMemory.nearest("王五丘", memory))
    }

    @Test
    fun nearestRejectsUnrelatedOrTooShort() {
        val memory = listOf("张三丰", "李四海")
        assertNull(NameMemory.nearest("冯二虎", memory))
        assertNull(NameMemory.nearest("张", memory))
        assertNull(NameMemory.nearest("完全不同的名字", memory))
    }

    @Test
    fun searchNeedsInputBeforeListingAnything() {
        val memory = listOf("张三丰", "李四海", "王五岳")

        assertEquals(emptyList<String>(), NameMemory.search("", memory))
        assertEquals(emptyList<String>(), NameMemory.search("   ", memory))
    }

    @Test
    fun searchRanksPrefixBeforeOtherMatches() {
        val memory = listOf("张三丰", "李四海", "王五岳", "张三")

        val matches = NameMemory.search("张", memory)

        assertEquals(listOf("张三", "张三丰"), matches)
    }

    @Test
    fun searchFindsBySubsequenceAndTypo() {
        val memory = listOf("张三丰", "李四海", "王五岳", "钱七曜")

        // 顺序子序列：三…丰
        assertEquals(listOf("张三丰"), NameMemory.search("三丰", memory))
        // 一位错字：钱七耀 → 钱七曜
        assertEquals(listOf("钱七曜"), NameMemory.search("钱七耀", memory))
    }

    @Test
    fun searchHonorsLimit() {
        val memory = (1..20).map { "示例$it" }

        assertEquals(6, NameMemory.search("示例", memory).size)
    }
}
