package com.bailensn.sort

import com.bailensn.sort.table.ColumnRole
import com.bailensn.sort.table.ColumnSpec
import com.bailensn.sort.table.TableHeader
import com.bailensn.sort.table.TableSelfCheck

/**
 * 认表头和算术自检：只吃字符串矩阵，不碰模型，所以不经过 [OcrBridge] 的句柄。
 * 逻辑全在 Native，这里只做类型搬运。
 */
object NativeTable {

    init {
        System.loadLibrary("main")
    }

    fun detectHeader(rows: List<List<String>>): TableHeader {
        if (rows.isEmpty()) return TableHeader()
        val parts = nativeDetectHeader(rows.toMatrix()) ?: return TableHeader()
        @Suppress("UNCHECKED_CAST")
        val names = parts[0] as? Array<String> ?: return TableHeader()
        val roles = parts[1] as? IntArray ?: IntArray(0)
        val fromImage = (parts[2] as? IntArray)?.firstOrNull() == 1
        val columns = names.indices.map { index ->
            ColumnSpec(index, names[index], roleOf(roles.getOrElse(index) { 0 }))
        }
        return TableHeader(columns, fromImage)
    }

    fun selfCheck(rows: List<List<String>>, header: TableHeader?): TableSelfCheck.Result {
        val parts = nativeSelfCheck(
            rows = rows.toMatrix(),
            totalColumn = header?.totalColumn ?: -1,
            subjectColumns = header?.subjectColumns?.toIntArray() ?: IntArray(0),
        ) ?: return TableSelfCheck.Result(rows, TableSelfCheck.Report())

        @Suppress("UNCHECKED_CAST")
        val fixed = (parts[0] as? Array<Array<String>>)?.map { it.toList() } ?: rows
        val notes = notesOf(parts[1] as? IntArray)
        val counts = parts[2] as? IntArray
        val suspicious = (parts[3] as? IntArray)?.toList() ?: emptyList()
        return TableSelfCheck.Result(
            rows = fixed,
            report = TableSelfCheck.Report(
                notes = notes,
                correctedCells = counts?.getOrNull(0) ?: 0,
                suspiciousRows = suspicious,
            ),
        )
    }

    /** C 侧的角色枚举顺序是 OTHER/SEQUENCE/ID/SUBJECT/TOTAL，和 Kotlin 的不同。 */
    private fun roleOf(code: Int): ColumnRole = when (code) {
        1 -> ColumnRole.SEQUENCE
        2 -> ColumnRole.ID
        3 -> ColumnRole.SUBJECT
        4 -> ColumnRole.TOTAL
        else -> ColumnRole.OTHER
    }

    private fun notesOf(raw: IntArray?): List<TableSelfCheck.Note> {
        if (raw == null || raw.size < 3) return emptyList()
        val notes = ArrayList<TableSelfCheck.Note>(raw.size / 3)
        var index = 0
        while (index + 2 < raw.size) {
            val a = raw[index + 1]
            val b = raw[index + 2]
            val note = when (raw[index]) {
                0 -> TableSelfCheck.Note.SequenceBreak(a, b)
                1 -> TableSelfCheck.Note.SequenceOk(a)
                2 -> TableSelfCheck.Note.IdBreak(a, b)
                3 -> TableSelfCheck.Note.SumChecked(a, b)
                4 -> TableSelfCheck.Note.PartialTotals(a)
                else -> null
            }
            if (note != null) notes += note
            index += 3
        }
        return notes
    }

    private fun List<List<String>>.toMatrix(): Array<Array<String>> =
        Array(size) { row -> this[row].toTypedArray() }

    private external fun nativeDetectHeader(rows: Array<Array<String>>): Array<Any>?

    private external fun nativeSelfCheck(
        rows: Array<Array<String>>,
        totalColumn: Int,
        subjectColumns: IntArray,
    ): Array<Any>?
}
