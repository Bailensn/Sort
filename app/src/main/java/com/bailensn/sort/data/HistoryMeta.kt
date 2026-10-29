package com.bailensn.sort.data

import com.bailensn.sort.table.TableSelfCheck

/**
 * 一次识别的过程数据，跟行数据一起存进历史；
 * 从历史打开时统计栏靠它回填，否则置信度、用时都是 0。
 */
data class HistoryMeta(
    val averageConfidence: Float = 0f,
    val lowConfidenceCells: Int = 0,
    val elapsedMs: Long = 0L,
    val cellMs: Long = 0L,
    val detectMs: Long = 0L,
    val correctedCells: Int = 0,
    val horizontalLines: Int = 0,
    val verticalLines: Int = 0,
    val cellCount: Int = 0,
    val deskewAngle: Double = 0.0,
    val rectified: Boolean = false,
    val suspiciousRows: List<Int> = emptyList(),
    val notes: List<TableSelfCheck.Note> = emptyList(),
) {

    fun report(): TableSelfCheck.Report = TableSelfCheck.Report(
        notes = notes,
        correctedCells = correctedCells,
        suspiciousRows = suspiciousRows,
    )

    fun encode(): String = buildString {
        append("v=").append(VERSION).append('\n')
        append("confidence=").append(averageConfidence).append('\n')
        append("low=").append(lowConfidenceCells).append('\n')
        append("elapsed=").append(elapsedMs).append('\n')
        append("cell=").append(cellMs).append('\n')
        append("detect=").append(detectMs).append('\n')
        append("corrected=").append(correctedCells).append('\n')
        append("hlines=").append(horizontalLines).append('\n')
        append("vlines=").append(verticalLines).append('\n')
        append("cells=").append(cellCount).append('\n')
        append("angle=").append(deskewAngle).append('\n')
        append("rectified=").append(if (rectified) 1 else 0).append('\n')
        append("suspicious=").append(suspiciousRows.joinToString(",")).append('\n')
        append("notes=").append(
            notes.joinToString("|") { note ->
                when (note) {
                    is TableSelfCheck.Note.SequenceBreak -> "sb:${note.column}:${note.breaks}"
                    is TableSelfCheck.Note.SequenceOk -> "so:${note.column}"
                    is TableSelfCheck.Note.IdBreak -> "ib:${note.column}:${note.breaks}"
                    is TableSelfCheck.Note.SumChecked -> "sc:${note.subjects}:${note.totalColumn}"
                    is TableSelfCheck.Note.PartialTotals -> "pt:${note.rows}"
                }
            },
        )
    }

    companion object {

        private const val VERSION = 1

        fun decode(text: String): HistoryMeta? {
            val fields = text.lineSequence()
                .mapNotNull { line ->
                    val split = line.indexOf('=')
                    if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
                }
                .toMap()
            if (fields["v"]?.toIntOrNull() != VERSION) return null
            return HistoryMeta(
                averageConfidence = fields["confidence"]?.toFloatOrNull() ?: 0f,
                lowConfidenceCells = fields["low"]?.toIntOrNull() ?: 0,
                elapsedMs = fields["elapsed"]?.toLongOrNull() ?: 0L,
                cellMs = fields["cell"]?.toLongOrNull() ?: 0L,
                detectMs = fields["detect"]?.toLongOrNull() ?: 0L,
                correctedCells = fields["corrected"]?.toIntOrNull() ?: 0,
                horizontalLines = fields["hlines"]?.toIntOrNull() ?: 0,
                verticalLines = fields["vlines"]?.toIntOrNull() ?: 0,
                cellCount = fields["cells"]?.toIntOrNull() ?: 0,
                deskewAngle = fields["angle"]?.toDoubleOrNull() ?: 0.0,
                rectified = fields["rectified"] == "1",
                suspiciousRows = fields["suspicious"].orEmpty()
                    .split(',')
                    .mapNotNull(String::toIntOrNull),
                notes = decodeNotes(fields["notes"].orEmpty()),
            )
        }

        private fun decodeNotes(raw: String): List<TableSelfCheck.Note> {
            if (raw.isEmpty()) return emptyList()
            return raw.split('|').mapNotNull { token ->
                val parts = token.split(':')
                when (parts.firstOrNull()) {
                    "sb" -> TableSelfCheck.Note.SequenceBreak(
                        column = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null,
                        breaks = parts.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null,
                    )

                    "so" -> TableSelfCheck.Note.SequenceOk(
                        column = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null,
                    )

                    "ib" -> TableSelfCheck.Note.IdBreak(
                        column = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null,
                        breaks = parts.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null,
                    )

                    "sc" -> TableSelfCheck.Note.SumChecked(
                        subjects = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null,
                        totalColumn = parts.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null,
                    )

                    "pt" -> TableSelfCheck.Note.PartialTotals(
                        rows = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null,
                    )

                    else -> null
                }
            }
        }
    }
}
