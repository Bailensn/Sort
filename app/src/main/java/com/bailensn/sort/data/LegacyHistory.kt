package com.bailensn.sort.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * 一次性搬迁：老版本把行数据放在 `history/entry-<ts>/rows.tsv`、过程数据放在 `meta.txt`。
 * 建库那天扫一遍搬进 SQLite，图片留在原地不动。
 */
internal object LegacyHistory {

    private const val ROOT = "history"
    private const val PREFIX = "entry-"
    private const val ROWS = "rows.tsv"
    private const val META = "meta.txt"

    fun importInto(db: SQLiteDatabase, context: android.content.Context?) {
        if (context == null) return
        runCatching {
            val root = File(context.filesDir, ROOT)
            root.listFiles { file -> file.isDirectory && file.name.startsWith(PREFIX) }
                .orEmpty()
                .forEach { folder -> importFolder(db, folder) }
        }
    }

    private fun importFolder(db: SQLiteDatabase, folder: File) {
        runCatching {
            val id = folder.name.removePrefix(PREFIX).toLongOrNull() ?: return
            val rowsFile = File(folder, ROWS)
            if (!rowsFile.isFile) return
            val rows = rowsFile.readLines().map { line -> line.split("\t") }
            if (rows.isEmpty()) return

            val metaFile = File(folder, META)
            val meta = metaFile.takeIf { it.isFile }?.let { HistoryMeta.decode(it.readText()) }
            val shots = HistoryStore.Shot.entries.fold(0) { acc, shot ->
                if (File(folder, shot.fileName).isFile) acc or shot.bit else acc
            }
            val values = ContentValues().apply {
                put(SortDatabase.COL_ID, id)
                put(SortDatabase.COL_CREATED, id)
                put(SortDatabase.COL_ROWS, rows.size)
                put(SortDatabase.COL_COLUMNS, rows.maxOfOrNull { it.size } ?: 0)
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
                put(SortDatabase.COL_SHOTS, shots)
            }
            if (db.insertWithOnConflict(
                    SortDatabase.TABLE_ENTRIES,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE,
                ) == -1L
            ) {
                return
            }

            val statement = db.compileStatement(
                "INSERT OR REPLACE INTO ${SortDatabase.TABLE_CELLS} " +
                    "(${SortDatabase.COL_ENTRY_ID}, ${SortDatabase.COL_ROW}, " +
                    "${SortDatabase.COL_COL}, ${SortDatabase.COL_TEXT}) VALUES (?, ?, ?, ?)",
            )
            rows.forEachIndexed { row, cells ->
                cells.forEachIndexed { column, text ->
                    statement.clearBindings()
                    statement.bindLong(1, id)
                    statement.bindLong(2, row.toLong())
                    statement.bindLong(3, column.toLong())
                    statement.bindString(4, text)
                    statement.executeInsert()
                }
            }
            statement.close()

            // 搬完的老文件删掉，图片留着。
            rowsFile.delete()
            metaFile.delete()
        }
    }
}
