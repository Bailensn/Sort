package com.bailensn.sort.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 历史记录：数据在 `sort.db`（一条记录 + 每格一行），三张图还在
 * `filesDir/history/entry-<id>/` 里，按 id 对应。
 */
object HistoryStore {

    private const val ROOT = "history"
    private const val MAX_ENTRIES = 20
    private const val MAX_SIDE = 1600
    private const val QUALITY = 88

    /** 界面上三种视图各自的图。 */
    enum class Shot(val fileName: String, val bit: Int) {
        ORIGINAL("original.jpg", 1),
        GRID("grid.jpg", 2),
        ENHANCED("enhanced.jpg", 4),
    }

    data class Entry(
        val id: Long,
        val timestamp: Long,
        val rowCount: Int,
        val columnCount: Int,
        val shots: Set<Shot> = emptySet(),
    )

    @Volatile
    private var helper: SortDatabase? = null

    private fun database(context: Context): SQLiteDatabase {
        val existing = helper
        if (existing != null) return existing.writableDatabase
        synchronized(this) {
            val created = helper ?: SortDatabase(context.applicationContext).also { helper = it }
            return created.writableDatabase
        }
    }

    fun list(context: Context): List<Entry> = runCatching {
        database(context).query(
            SortDatabase.TABLE_ENTRIES,
            null,
            null,
            null,
            null,
            null,
            "${SortDatabase.COL_CREATED} DESC",
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(SortDatabase.COL_ID)
            val createdIndex = cursor.getColumnIndexOrThrow(SortDatabase.COL_CREATED)
            val rowsIndex = cursor.getColumnIndexOrThrow(SortDatabase.COL_ROWS)
            val columnsIndex = cursor.getColumnIndexOrThrow(SortDatabase.COL_COLUMNS)
            val shotsIndex = cursor.getColumnIndexOrThrow(SortDatabase.COL_SHOTS)
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Entry(
                            id = cursor.getLong(idIndex),
                            timestamp = cursor.getLong(createdIndex),
                            rowCount = cursor.getInt(rowsIndex),
                            columnCount = cursor.getInt(columnsIndex),
                            shots = decodeShots(cursor.getInt(shotsIndex)),
                        ),
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    fun rows(context: Context, entry: Entry): List<List<String>> = rows(context, entry.id)

    fun rows(context: Context, id: Long): List<List<String>> = runCatching {
        database(context).query(
            SortDatabase.TABLE_CELLS,
            arrayOf(SortDatabase.COL_ROW, SortDatabase.COL_COL, SortDatabase.COL_TEXT),
            "${SortDatabase.COL_ENTRY_ID} = ?",
            arrayOf(id.toString()),
            null,
            null,
            "${SortDatabase.COL_ROW}, ${SortDatabase.COL_COL}",
        ).use { cursor ->
            val rowIndex = cursor.getColumnIndexOrThrow(SortDatabase.COL_ROW)
            val colIndex = cursor.getColumnIndexOrThrow(SortDatabase.COL_COL)
            val textIndex = cursor.getColumnIndexOrThrow(SortDatabase.COL_TEXT)
            val grid = ArrayList<MutableList<String>>()
            while (cursor.moveToNext()) {
                val row = cursor.getInt(rowIndex)
                val column = cursor.getInt(colIndex)
                while (grid.size <= row) grid.add(mutableListOf())
                val current = grid[row]
                while (current.size <= column) current.add("")
                current[column] = cursor.getString(textIndex)
            }
            grid.map { it.toList() }
        }
    }.getOrDefault(emptyList())

    /** 读某一种视图的图；那条记录没存这张就返回 null。 */
    fun shot(context: Context, entry: Entry, shot: Shot): Bitmap? = runCatching {
        File(folder(context, entry.id), shot.fileName)
            .takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }.getOrNull()

    /** 读过程数据；没有这条记录就返回 null。 */
    fun meta(context: Context, entry: Entry): HistoryMeta? = runCatching {
        database(context).query(
            SortDatabase.TABLE_ENTRIES,
            null,
            "${SortDatabase.COL_ID} = ?",
            arrayOf(entry.id.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            HistoryMeta(
                averageConfidence = cursor.getFloat(cursor.getColumnIndexOrThrow(SortDatabase.COL_CONFIDENCE)),
                lowConfidenceCells = cursor.getInt(cursor.getColumnIndexOrThrow(SortDatabase.COL_LOW_CONFIDENCE)),
                elapsedMs = cursor.getLong(cursor.getColumnIndexOrThrow(SortDatabase.COL_ELAPSED)),
                cellMs = cursor.getLong(cursor.getColumnIndexOrThrow(SortDatabase.COL_CELL_MS)),
                detectMs = cursor.getLong(cursor.getColumnIndexOrThrow(SortDatabase.COL_DETECT_MS)),
                correctedCells = cursor.getInt(cursor.getColumnIndexOrThrow(SortDatabase.COL_CORRECTED)),
                horizontalLines = cursor.getInt(cursor.getColumnIndexOrThrow(SortDatabase.COL_H_LINES)),
                verticalLines = cursor.getInt(cursor.getColumnIndexOrThrow(SortDatabase.COL_V_LINES)),
                cellCount = cursor.getInt(cursor.getColumnIndexOrThrow(SortDatabase.COL_CELL_COUNT)),
                deskewAngle = cursor.getDouble(cursor.getColumnIndexOrThrow(SortDatabase.COL_ANGLE)),
                rectified = cursor.getInt(cursor.getColumnIndexOrThrow(SortDatabase.COL_RECTIFIED)) == 1,
                suspiciousRows = HistoryMeta.decodeInts(
                    cursor.getString(cursor.getColumnIndexOrThrow(SortDatabase.COL_SUSPICIOUS)).orEmpty(),
                ),
                notes = HistoryMeta.decodeNotes(
                    cursor.getString(cursor.getColumnIndexOrThrow(SortDatabase.COL_NOTES)).orEmpty(),
                ),
            )
        }
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
        val folder = folder(context, timestamp)
        folder.mkdirs()
        val savedShots = buildSet {
            writeShot(folder, Shot.ORIGINAL, original)?.let(::add)
            writeShot(folder, Shot.GRID, grid)?.let(::add)
            writeShot(folder, Shot.ENHANCED, enhanced)?.let(::add)
        }
        val width = rows.maxOfOrNull { it.size } ?: 0
        val values = ContentValues().apply {
            put(SortDatabase.COL_ID, timestamp)
            put(SortDatabase.COL_CREATED, timestamp)
            put(SortDatabase.COL_ROWS, rows.size)
            put(SortDatabase.COL_COLUMNS, width)
            put(SortDatabase.COL_CONFIDENCE, meta?.averageConfidence ?: 0f)
            put(SortDatabase.COL_LOW_CONFIDENCE, meta?.lowConfidenceCells ?: 0)
            put(SortDatabase.COL_ELAPSED, meta?.elapsedMs ?: 0L)
            put(SortDatabase.COL_CELL_MS, meta?.cellMs ?: 0L)
            put(SortDatabase.COL_DETECT_MS, meta?.detectMs ?: 0L)
            put(SortDatabase.COL_CORRECTED, meta?.correctedCells ?: 0)
            put(SortDatabase.COL_H_LINES, meta?.horizontalLines ?: 0)
            put(SortDatabase.COL_V_LINES, meta?.verticalLines ?: 0)
            put(SortDatabase.COL_CELL_COUNT, meta?.cellCount ?: 0)
            put(SortDatabase.COL_ANGLE, meta?.deskewAngle ?: 0.0)
            put(SortDatabase.COL_RECTIFIED, if (meta?.rectified == true) 1 else 0)
            put(SortDatabase.COL_SUSPICIOUS, HistoryMeta.encodeInts(meta?.suspiciousRows.orEmpty()))
            put(SortDatabase.COL_NOTES, HistoryMeta.encodeNotes(meta?.notes.orEmpty()))
            put(SortDatabase.COL_SHOTS, savedShots.fold(0) { acc, shot -> acc or shot.bit })
        }
        val db = database(context)
        db.beginTransaction()
        try {
            db.insertOrThrow(SortDatabase.TABLE_ENTRIES, null, values)
            insertCells(db, timestamp, rows)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        prune(context)
        Entry(
            id = timestamp,
            timestamp = timestamp,
            rowCount = rows.size,
            columnCount = width,
            shots = savedShots,
        )
    }.getOrNull()

    /** 手动改格子后回写：整条记录的格子重写一遍。 */
    fun update(context: Context, id: Long, rows: List<List<String>>) {
        if (rows.isEmpty()) return
        runCatching {
            val db = database(context)
            db.beginTransaction()
            try {
                db.delete(SortDatabase.TABLE_CELLS, "${SortDatabase.COL_ENTRY_ID} = ?", arrayOf(id.toString()))
                insertCells(db, id, rows)
                val values = ContentValues().apply {
                    put(SortDatabase.COL_ROWS, rows.size)
                    put(SortDatabase.COL_COLUMNS, rows.maxOfOrNull { it.size } ?: 0)
                }
                db.update(
                    SortDatabase.TABLE_ENTRIES,
                    values,
                    "${SortDatabase.COL_ID} = ?",
                    arrayOf(id.toString()),
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun delete(context: Context, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        runCatching {
            val db = database(context)
            db.beginTransaction()
            try {
                ids.forEach { id ->
                    db.delete(SortDatabase.TABLE_CELLS, "${SortDatabase.COL_ENTRY_ID} = ?", arrayOf(id.toString()))
                    db.delete(SortDatabase.TABLE_ENTRIES, "${SortDatabase.COL_ID} = ?", arrayOf(id.toString()))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            ids.forEach { id -> runCatching { folder(context, id).deleteRecursively() } }
        }
    }

    fun formatTime(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

    private fun insertCells(db: SQLiteDatabase, entryId: Long, rows: List<List<String>>) {
        val statement = db.compileStatement(
            "INSERT OR REPLACE INTO ${SortDatabase.TABLE_CELLS} " +
                "(${SortDatabase.COL_ENTRY_ID}, ${SortDatabase.COL_ROW}, " +
                "${SortDatabase.COL_COL}, ${SortDatabase.COL_TEXT}) VALUES (?, ?, ?, ?)",
        )
        rows.forEachIndexed { row, cells ->
            cells.forEachIndexed { column, text ->
                statement.clearBindings()
                statement.bindLong(1, entryId)
                statement.bindLong(2, row.toLong())
                statement.bindLong(3, column.toLong())
                statement.bindString(4, text)
                statement.executeInsert()
            }
        }
        statement.close()
    }

    private fun root(context: Context): File = File(context.filesDir, ROOT)

    private fun folder(context: Context, id: Long): File = File(root(context), "entry-$id")

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
        runCatching {
            val db = database(context)
            val stale = db.query(
                SortDatabase.TABLE_ENTRIES,
                arrayOf(SortDatabase.COL_ID),
                null,
                null,
                null,
                null,
                "${SortDatabase.COL_CREATED} DESC LIMIT -1 OFFSET $MAX_ENTRIES",
            ).use { cursor ->
                val index = cursor.getColumnIndexOrThrow(SortDatabase.COL_ID)
                buildList { while (cursor.moveToNext()) add(cursor.getLong(index)) }
            }
            if (stale.isNotEmpty()) delete(context, stale)
        }
    }

    private fun decodeShots(bits: Int): Set<Shot> =
        Shot.entries.filterTo(mutableSetOf()) { bits and it.bit != 0 }
}
