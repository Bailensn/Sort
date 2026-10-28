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
