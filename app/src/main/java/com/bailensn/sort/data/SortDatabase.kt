package com.bailensn.sort.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 识别结果库：一条记录 + 每格一行，图片仍然放在 filesDir 的文件夹里。 */
internal class SortDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    private val appContext: Context = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_ENTRIES (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_CREATED INTEGER NOT NULL,
                $COL_ROWS INTEGER NOT NULL,
                $COL_COLUMNS INTEGER NOT NULL,
                $COL_CONFIDENCE REAL NOT NULL DEFAULT 0,
                $COL_LOW_CONFIDENCE INTEGER NOT NULL DEFAULT 0,
                $COL_ELAPSED INTEGER NOT NULL DEFAULT 0,
                $COL_CELL_MS INTEGER NOT NULL DEFAULT 0,
                $COL_DETECT_MS INTEGER NOT NULL DEFAULT 0,
                $COL_CORRECTED INTEGER NOT NULL DEFAULT 0,
                $COL_H_LINES INTEGER NOT NULL DEFAULT 0,
                $COL_V_LINES INTEGER NOT NULL DEFAULT 0,
                $COL_CELL_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_ANGLE REAL NOT NULL DEFAULT 0,
                $COL_RECTIFIED INTEGER NOT NULL DEFAULT 0,
                $COL_SUSPICIOUS TEXT NOT NULL DEFAULT '',
                $COL_NOTES TEXT NOT NULL DEFAULT '',
                $COL_SHOTS INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_CELLS (
                $COL_ENTRY_ID INTEGER NOT NULL,
                $COL_ROW INTEGER NOT NULL,
                $COL_COL INTEGER NOT NULL,
                $COL_TEXT TEXT NOT NULL,
                PRIMARY KEY ($COL_ENTRY_ID, $COL_ROW, $COL_COL)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX ${TABLE_CELLS}_entry ON $TABLE_CELLS ($COL_ENTRY_ID)")
        LegacyHistory.importInto(db, appContext)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        const val NAME = "sort.db"
        const val VERSION = 1

        const val TABLE_ENTRIES = "entries"
        const val TABLE_CELLS = "cells"

        const val COL_ID = "id"
        const val COL_CREATED = "created_at"
        const val COL_ROWS = "row_count"
        const val COL_COLUMNS = "column_count"
        const val COL_CONFIDENCE = "average_confidence"
        const val COL_LOW_CONFIDENCE = "low_confidence"
        const val COL_ELAPSED = "elapsed_ms"
        const val COL_CELL_MS = "cell_ms"
        const val COL_DETECT_MS = "detect_ms"
        const val COL_CORRECTED = "corrected"
        const val COL_H_LINES = "h_lines"
        const val COL_V_LINES = "v_lines"
        const val COL_CELL_COUNT = "cells"
        const val COL_ANGLE = "angle"
        const val COL_RECTIFIED = "rectified"
        const val COL_SUSPICIOUS = "suspicious"
        const val COL_NOTES = "notes"
        const val COL_SHOTS = "shots"

        const val COL_ENTRY_ID = "entry_id"
        const val COL_ROW = "row_index"
        const val COL_COL = "col_index"
        const val COL_TEXT = "text"
    }
}
