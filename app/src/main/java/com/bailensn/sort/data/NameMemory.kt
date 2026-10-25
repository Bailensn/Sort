package com.bailensn.sort.data

import android.content.Context
import java.io.File

/**
 * 人名记忆：记住用户确认过的姓名，下次识别时拿它纠错。
 *
 * 成绩单里的姓名是生僻字重灾区（`蒴`、`熇`、`忱`），模型的字表救不了，
 * 但同一个班的名字年年重复——认过一次、你确认过，下次就能按编辑距离纠回来。
 *
 * 存储用一行一个名字的纯文本，几十个名字，读写都是毫秒级。
 */
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

    /**
     * 在记忆里找一个和 [candidate] 足够接近的名字。
     *
     * 只允许一个字的差异，而且两边长度都得 ≥2——汉字一个字的差别往往就是同一个人
     * 被认错（`庄诗严` / `荘诗严`），但两个字的差异已经可能是别人了。
     */
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
