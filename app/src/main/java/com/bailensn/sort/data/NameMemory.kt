package com.bailensn.sort.data

import android.content.Context
import java.io.File

object NameMemory {

    private const val FILE_NAME = "names.txt"
    private const val MAX_NAMES = 500

    fun load(context: Context): List<String> =
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.isFile) emptyList() else file.readLines().filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

    fun remember(context: Context, names: Collection<String>) {
        if (names.isEmpty()) return
        runCatching {
            val merged = (load(context) + names.map { it.trim() })
                .filter { it.isNotBlank() && it.length in 2..4 }
                .distinct()
                .takeLast(MAX_NAMES)
            File(context.filesDir, FILE_NAME).writeText(merged.joinToString("\n"))
        }
    }

    fun nearest(candidate: String, memory: List<String>): String? {
        val target = candidate.trim()
        if (target.length < 2) return null
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (name in memory) {
            if (name.length < 2) continue
            if (name == target) return name
            if (kotlin.math.abs(name.length - target.length) > 1) continue
            val distance = editDistance(name, target)
            if (distance < bestDistance) {
                bestDistance = distance
                best = name
            }
        }
        return if (bestDistance <= 1) best else null
    }

    /**
     * 按已输入的字模糊找名字：前缀 > 包含 > 顺序子序列 > 编辑距离 1。
     * 界面只在开始输入之后才列出来，不是一上来就把整份名单倒给用户。
     */
    fun search(query: String, memory: List<String>, limit: Int = 6): List<String> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        val scored = memory.mapNotNull { name ->
            val score = scoreName(text, name) ?: return@mapNotNull null
            score to name
        }
        return scored.sortedWith(compareBy({ it.first }, { it.second })).map { it.second }.take(limit)
    }

    /** 分数越小越像；完全不像返回 null。 */
    private fun scoreName(query: String, name: String): Int? {
        if (name.length < 2 || name == query) return null
        if (name.startsWith(query)) return 0
        if (name.contains(query)) return 1
        if (query.length >= 2 && isSubsequence(query, name)) return 2
        if (kotlin.math.abs(name.length - query.length) <= 1 &&
            editDistance(name, query) <= 1
        ) {
            return 3
        }
        return null
    }

    private fun isSubsequence(query: String, name: String): Boolean {
        var index = 0
        for (character in name) {
            if (index < query.length && character == query[index]) index++
            if (index == query.length) return true
        }
        return false
    }

    /** 标准 Levenshtein 距离，短字符串够用。 */
    fun editDistance(a: String, b: String): Int {
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost,
                )
            }
            previous.indices.forEach { previous[it] = current[it] }
        }
        return previous[b.length]
    }
}
