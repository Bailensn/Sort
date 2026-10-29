package com.bailensn.sort.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 一条记录一个文件夹，里面是 `rows.tsv` 和三张图（原图 / 网格 / 增强）。
 * 存图统一缩到 1600px，二十条记录也不会把磁盘吃光。
 */
object HistoryStore {

    private const val ROOT = "history"
    private const val PREFIX = "entry-"
    private const val ROWS = "rows.tsv"
    private const val META = "meta.txt"
    private const val MAX_ENTRIES = 20
    private const val MAX_SIDE = 1600
    private const val QUALITY = 88

    /** 界面上三种视图各自的图。 */
    enum class Shot(val fileName: String) {
        ORIGINAL("original.jpg"),
        GRID("grid.jpg"),
        ENHANCED("enhanced.jpg"),
    }

    data class Entry(
        val id: String,
        val timestamp: Long,
        val rowCount: Int,
        val columnCount: Int,
        val shots: Set<Shot> = emptySet(),
    )

    fun list(context: Context): List<Entry> = runCatching {
        val root = rootDir(context)
        root.listFiles { file -> file.isDirectory && file.name.startsWith(PREFIX) }
            .orEmpty()
            .sortedByDescending { it.name }
            .mapNotNull { folder ->
                val timestamp = folder.name.removePrefix(PREFIX).toLongOrNull()
                    ?: return@mapNotNull null
                val lines = File(folder, ROWS).takeIf { it.isFile }?.readLines().orEmpty()
                if (lines.isEmpty()) return@mapNotNull null
                Entry(
                    id = folder.name,
                    timestamp = timestamp,
                    rowCount = lines.size,
                    // 取最宽的一行：中间某行多出一个分隔符时，列数不会因此变少。
                    columnCount = lines.maxOfOrNull { line -> line.count { it == '\t' } + 1 } ?: 0,
                    shots = Shot.entries.filterTo(mutableSetOf()) { File(folder, it.fileName).isFile },
                )
            }
    }.getOrDefault(emptyList())

    fun rows(context: Context, entry: Entry): List<List<String>> = rows(context, entry.id)

    fun rows(context: Context, id: String): List<List<String>> = runCatching {
        File(folder(context, id), ROWS).readLines().map { it.split("\t") }
    }.getOrDefault(emptyList())

    /** 读过程数据；老记录没有就返回 null。 */
    fun meta(context: Context, entry: Entry): HistoryMeta? = runCatching {
        File(folder(context, entry.id), META)
            .takeIf { it.isFile }
            ?.let { HistoryMeta.decode(it.readText()) }
    }.getOrNull()

    /** 读某一种视图的图；那条记录没存这张就返回 null。 */
    fun shot(context: Context, entry: Entry, shot: Shot): Bitmap? = runCatching {
        File(folder(context, entry.id), shot.fileName)
            .takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }.getOrNull()

    fun save(
        context: Context,
        rows: List<List<String>>,
        original: Bitmap? = null,
        grid: Bitmap? = null,
        enhanced: Bitmap? = null,
        meta: HistoryMeta? = null,
    ): Entry? = runCatching {
        if (rows.isEmpty()) return null
        val timestamp = System.currentTimeMillis()
        val folder = folder(context, "$PREFIX$timestamp")
        folder.mkdirs()
        File(folder, ROWS).writeText(rows.joinToString("\n") { row -> row.joinToString("\t") })
        if (meta != null) {
            runCatching { File(folder, META).writeText(meta.encode()) }
        }
        val saved = buildSet {
            writeShot(folder, Shot.ORIGINAL, original)?.let { add(it) }
            writeShot(folder, Shot.GRID, grid)?.let { add(it) }
            writeShot(folder, Shot.ENHANCED, enhanced)?.let { add(it) }
        }
        prune(context)
        Entry(
            id = folder.name,
            timestamp = timestamp,
            rowCount = rows.size,
            columnCount = rows.maxOfOrNull { it.size } ?: 0,
            shots = saved,
        )
    }.getOrNull()

    /** 手动改格子后回写，历史记录里看到的就是改过的版本。 */
    fun update(context: Context, entryId: String, rows: List<List<String>>) {
        runCatching {
            if (rows.isEmpty()) return
            val folder = folder(context, entryId)
            if (!folder.isDirectory) return
            File(folder, ROWS).writeText(rows.joinToString("\n") { row -> row.joinToString("\t") })
        }
    }

    fun delete(context: Context, ids: Collection<String>) {
        ids.forEach { id -> runCatching { folder(context, id).deleteRecursively() } }
    }

    private fun rootDir(context: Context): File = File(context.filesDir, ROOT)

    private fun folder(context: Context, id: String): File = File(rootDir(context), id)

    /** 写一张图，返回真正写成功的 [Shot]，写失败就当没存。 */
    private fun writeShot(folder: File, shot: Shot, bitmap: Bitmap?): Shot? {
        if (bitmap == null || bitmap.isRecycled) return null
        return runCatching {
            val scaled = scaleDown(bitmap)
            File(folder, shot.fileName).outputStream().use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) {
                    throw IllegalStateException("compress failed")
                }
            }
            // 缩放副本自己回收，原图归流水线管。
            if (scaled !== bitmap) scaled.recycle()
            shot
        }.getOrNull()
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_SIDE) return bitmap
        val ratio = MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun prune(context: Context) {
        val root = rootDir(context)
        root.listFiles { file -> file.isDirectory && file.name.startsWith(PREFIX) }
            .orEmpty()
            .sortedByDescending { it.name }
            .drop(MAX_ENTRIES)
            .forEach { folder -> runCatching { folder.deleteRecursively() } }
    }

    fun formatTime(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}
