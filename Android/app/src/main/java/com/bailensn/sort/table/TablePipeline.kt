package com.bailensn.sort.table

import android.graphics.Bitmap
import com.bailensn.sort.TableNote
import com.bailensn.sort.TableProgress
import com.bailensn.sort.ocr.NativeOcr

/**
 * 表格流水线的门面：裁切、纠偏、找线、切格、多遍识别投票、清洗、表头、自检
 * 全在 Native 的 analyzeTable 里跑完，这里只把结果翻译成界面用的形状。
 */
object TablePipeline {

    enum class Stage { RECTIFY, SEGMENT, RECOGNIZE, VERIFY }

    data class Analysis(
        val overlay: Bitmap,
        val grid: TableGrid,
        val deskewAngle: Double,
        val rectified: Boolean,
        val correctedCells: Int,
        val elapsedMs: Long,
        /** 诊断用：检测到的横线条数。 */
        val horizontalLines: Int,
        /** 诊断用：检测到的竖线条数。 */
        val verticalLines: Int,
        /** 诊断用：线切出来的单元格数。 */
        val cellCount: Int,
        /** 逐格识别耗时（含多遍投票）。 */
        val cellMs: Long = 0L,
        /** 整图回读耗时。 */
        val detectMs: Long = 0L,
        /** 增强后的灰度图：历史里存的那张，也给用户看。 */
        val enhanced: Bitmap? = null,
        /** 有墨迹格子的平均置信度。 */
        val averageConfidence: Float = 0f,
        /** 置信度的最低/最高值，界面上和均值一起显示，方便判断是不是被固定住了。 */
        val confidenceMin: Float = 0f,
        val confidenceMax: Float = 0f,
        /** 多遍走完仍低于可信线的格子数。 */
        val lowConfidenceCells: Int = 0,
        /** 算术自检的结论（序号/学号连续、求和校验、反推纠正）。 */
        val selfCheck: TableSelfCheck.Report = TableSelfCheck.Report(),
        /** 表头结构：每列的列名与身份，界面上的结构卡片靠它。 */
        val header: TableHeader = TableHeader(),
    ) {
        /** 除识别以外的耗时：纠偏、线检测、切格、画网格。 */
        val structureMs: Long get() = (elapsedMs - cellMs).coerceAtLeast(0L)
    }

    suspend fun analyze(
        ocr: NativeOcr,
        source: Bitmap,
        onStage: (Stage) -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): Analysis {
        // Native 一边跑一边报阶段，界面不会一直停在「校正」上。
        onStage(Stage.RECTIFY)
        onProgress(0f)

        val result = ocr.analyzeTable(
            source,
            TableProgress { stage, fraction ->
                onStage(
                    when (stage) {
                        0 -> Stage.RECTIFY
                        1 -> Stage.SEGMENT
                        2 -> Stage.RECOGNIZE
                        else -> Stage.VERIFY
                    },
                )
                onProgress(fraction)
            },
        )

        val header = TableHeader(
            columns = result.headerNames.mapIndexed { index, name ->
                ColumnSpec(index, name, roleOf(result.headerRoles.getOrElse(index) { 0 }))
            },
            fromImage = result.headerFromImage,
        )
        return Analysis(
            overlay = result.overlay ?: source,
            grid = TableGrid(
                rows = result.rows,
                mode = if (result.byLines) TableGrid.Mode.GRID_LINES else TableGrid.Mode.TEXT_CLUSTER,
            ),
            deskewAngle = result.deskewDegrees,
            rectified = result.rectified,
            correctedCells = result.correctedCells,
            elapsedMs = result.elapsedMs,
            horizontalLines = result.horizontalLines,
            verticalLines = result.verticalLines,
            cellCount = result.cellCount,
            cellMs = result.cellMs,
            detectMs = result.detectMs,
            enhanced = result.enhanced,
            averageConfidence = result.averageConfidence,
            confidenceMin = result.confidenceMin,
            confidenceMax = result.confidenceMax,
            lowConfidenceCells = result.lowConfidenceCells,
            selfCheck = TableSelfCheck.Report(
                notes = notesOf(result.notes),
                correctedCells = result.correctedCells,
                suspiciousRows = result.suspiciousRows,
            ),
            header = header,
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

    private fun notesOf(raw: List<TableNote>): List<TableSelfCheck.Note> = raw.mapNotNull { note ->
        when (note.kind) {
            0 -> TableSelfCheck.Note.SequenceBreak(note.a, note.b)
            1 -> TableSelfCheck.Note.SequenceOk(note.a)
            2 -> TableSelfCheck.Note.IdBreak(note.a, note.b)
            3 -> TableSelfCheck.Note.SumChecked(note.a, note.b)
            4 -> TableSelfCheck.Note.PartialTotals(note.a)
            else -> null
        }
    }
}
