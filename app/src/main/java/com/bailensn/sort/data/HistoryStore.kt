package com.bailensn.sort.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识别历史：把每次识别出的表格落一份到内部存储，最多留 [MAX_ENTRIES] 份。
 *
 * 存成 TSV（一行一条记录，制表符分隔），不需要 JSON 库，读回来就是矩阵。
 * 表格里不会出现制表符和换行，所以这个格式足够安全。
 */
object HistoryStore {

    private const val MAX_ENTRIES = 20
    private const val PREFIX = "history-"
    private const val SUFFIX = ".tsv"

    data class Entry(val id: String, val timestamp: Long, val rowCount: Int, val columnCount: Int)

    fun list(context: Context): List<Entry> = runCatching {
        val dir = context.filesDir
        dir.listFiles { file -> file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) }
            .orEmpty()
            .sortedByDescending { it.name }
            .mapNotNull { file ->
                val lines = file.readLines()
                val timestamp = file.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull()
                    ?: return@mapNotNull null
                Entry(
                    id = file.name,
                    timestamp = timestamp,
                    rowCount = lines.size,
                    columnCount = lines.maxOfOrNull { it.count { _ -> true } } ?: 0,
                )
            }
    }.getOrDefault(emptyList())

    fun rows(context: Context, entry: Entry): List<List<String>> = runCatching {
        File(context.filesDir, entry.id).readLines().map { it.split("\t") }
    }.getOrDefault(emptyList())

    fun save(context: Context, rows: List<List<String>>): Entry? = runCatching {
        if (rows.isEmpty()) return null
        val timestamp = System.currentTimeMillis()
        val file = File(context.filesDir, "$PREFIX$timestamp$SUFFIX")
        file.writeText(rows.joinToString("\n") { row -> row.joinToString("\t") })
        prune(context)
        Entry(
            id = file.name,
            timestamp = timestamp,
            rowCount = rows.size,
            columnCount = rows.maxOfOrNull { it.size } ?: 0,
        )
    }.getOrNull()

    private fun prune(context: Context) {
        val files = context.filesDir
            .listFiles { file -> file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) }
            .orEmpty()
            .sortedByDescending { it.name }
        files.drop(MAX_ENTRIES).forEach { runCatching { it.delete() } }
    }

    fun formatTime(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}
