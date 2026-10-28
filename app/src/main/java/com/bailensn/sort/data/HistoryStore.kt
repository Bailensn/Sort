package com.bailensn.sort.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HistoryStore {

    private const val MAX_ENTRIES = 20
    private const val PREFIX = "history-"
    private const val SUFFIX = ".tsv"

    data class Entry(
        val id: String,
        val timestamp: Long,
        val rowCount: Int,
        val columnCount: Int,
        val hasImage: Boolean = false,
    )

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
                    columnCount = lines.firstOrNull()?.count { it == '\t' }?.plus(1) ?: 0,
                    hasImage = File(dir, imageName(file.name)).isFile,
                )
            }
    }.getOrDefault(emptyList())

    fun rows(context: Context, entry: Entry): List<List<String>> = runCatching {
        File(context.filesDir, entry.id).readLines().map { it.split("\t") }
    }.getOrDefault(emptyList())

    fun save(context: Context, rows: List<List<String>>, original: Bitmap? = null): Entry? = runCatching {
        if (rows.isEmpty()) return null
        val timestamp = System.currentTimeMillis()
        val file = File(context.filesDir, "$PREFIX$timestamp$SUFFIX")
        file.writeText(rows.joinToString("\n") { row -> row.joinToString("\t") })
        if (original != null) {
            runCatching {
                File(context.filesDir, imageName(file.name)).outputStream().use { out ->
                    original.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
        }
        prune(context)
        Entry(
            id = file.name,
            timestamp = timestamp,
            rowCount = rows.size,
            columnCount = rows.maxOfOrNull { it.size } ?: 0,
            hasImage = original != null,
        )
    }.getOrNull()

    /** 手动改格子后回写，历史记录里看到的就是改过的版本。 */
    fun update(context: Context, entryId: String, rows: List<List<String>>) {
        runCatching {
            if (rows.isEmpty()) return
            File(context.filesDir, entryId).writeText(
                rows.joinToString("\n") { row -> row.joinToString("\t") },
            )
        }
    }

    /** 读回原图；没有就返回 null（旧记录、或当时没存）。 */
    fun image(context: Context, entry: Entry): Bitmap? = runCatching {
        val file = File(context.filesDir, imageName(entry.id))
        if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
    }.getOrNull()

    private fun imageName(tsvName: String): String =
        tsvName.removeSuffix(SUFFIX) + ".jpg"

    private fun prune(context: Context) {
        val files = context.filesDir
            .listFiles { file -> file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) }
            .orEmpty()
            .sortedByDescending { it.name }
        files.drop(MAX_ENTRIES).forEach { file ->
            runCatching { file.delete() }
            runCatching { File(file.parentFile, imageName(file.name)).delete() }
        }
    }

    fun formatTime(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}
