package com.bailensn.sort.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 注意：这里所有姓名都是**虚构占位名**（示例甲/同学A）。
 * 任何真实人名都不允许出现在本仓库，包括测试数据、注释和示例。
 */
class NameMemoryTest {

    @Test
    fun editDistanceCountsSingleCharacterSwap() {
        assertEquals(1, NameMemory.editDistance("示例甲", "示例乙"))
        assertEquals(0, NameMemory.editDistance("同学A", "同学A"))
        assertEquals(1, NameMemory.editDistance("同学C", "同学D"))
    }

    @Test
    fun nearestFixesOneCharacterMisread() {
        val memory = listOf("示例甲", "同学A")
        assertEquals("示例甲", NameMemory.nearest("示例乙", memory))
        assertEquals("同学A", NameMemory.nearest("同学B", memory))
    }

    @Test
    fun nearestRejectsUnrelatedOrTooShort() {
        val memory = listOf("示例甲", "同学A")
        assertNull(NameMemory.nearest("丙同学", memory))
        assertNull(NameMemory.nearest("同", memory))
        assertNull(NameMemory.nearest("完全不同的名字", memory))
    }

    @Test
    fun searchNeedsInputBeforeListingAnything() {
        val memory = listOf("示例甲", "同学A", "同学B")

        assertEquals(emptyList<String>(), NameMemory.search("", memory))
        assertEquals(emptyList<String>(), NameMemory.search("   ", memory))
    }

    @Test
    fun searchRanksPrefixBeforeOtherMatches() {
        val memory = listOf("同学B", "同学A", "丙同学", "同学C")

        val matches = NameMemory.search("同学", memory)

        assertEquals(listOf("同学A", "同学B", "同学C", "丙同学"), matches)
    }

    @Test
    fun searchFindsBySubsequenceAndTypo() {
        val memory = listOf("示例甲", "同学A", "同学B")

        // 顺序子序列：示…甲
        assertEquals(listOf("示例甲"), NameMemory.search("示甲", memory))
        // 一位错字：示例已 → 示例甲
        assertEquals(listOf("示例甲"), NameMemory.search("示例已", memory))
    }

    @Test
    fun searchHonorsLimit() {
        val memory = (1..20).map { "示例$it" }

        assertEquals(6, NameMemory.search("示例", memory).size)
    }
}
