package com.bailensn.sort.table

import com.bailensn.sort.NativeTable

object TableSelfCheck {

    data class Report(
        /** 自检发现的约束明细，由界面翻译成文案。 */
        val notes: List<Note> = emptyList(),
        /** 靠算术反推纠正的格子数。 */
        val correctedCells: Int = 0,
        /** 仍然对不上的行号（0 基）。 */
        val suspiciousRows: List<Int> = emptyList(),
    )

    /** 自检结论。列号与行号都是 0 基，展示时再加一。 */
    sealed interface Note {
        data class SequenceBreak(val column: Int, val breaks: Int) : Note
        data class SequenceOk(val column: Int) : Note
        data class IdBreak(val column: Int, val breaks: Int) : Note
        data class SumChecked(val subjects: Int, val totalColumn: Int) : Note

        /** 只考了部分科目、总分按已考科目补算的行数。 */
        data class PartialTotals(val rows: Int) : Note
    }

    data class Result(val rows: List<List<String>>, val report: Report)

    /**
     * header 是用户/图片确认过的列结构：给了总分列和科目列就直接用，
     * 不再靠「最右一列是总分」这类猜测。实现在 Native。
     */
    fun check(rows: List<List<String>>, header: TableHeader? = null): Result =
        NativeTable.selfCheck(rows, header)
}
