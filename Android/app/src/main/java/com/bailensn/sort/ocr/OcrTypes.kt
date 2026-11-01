package com.bailensn.sort.ocr

import android.graphics.PointF

/** 四点框，顺序固定为左上、右上、右下、左下。 */
data class OcrBox(val points: List<PointF>)

/** 整图识别的一条结果。 */
data class OcrLine(
    val box: OcrBox,
    val text: String,
    val confidence: Float,
)

data class OcrRun(val results: List<OcrLine>)

/** 与 ppocr-sdk 里那份实现保持一致：去线头、去汉字间空格。 */
object CellText {

    private const val EDGE_NOISE = "|｜丨_—–-·•.,，。:：;；'\"`~^"
    private const val LINE_LIKE = "|｜丨_—–-1lI"

    fun clean(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        text = text.trim { it in EDGE_NOISE }
        if (text.isEmpty()) return ""

        val builder = StringBuilder(text.length)
        for (index in text.indices) {
            val character = text[index]
            if (character == ' ') {
                val previous = builder.lastOrNull()
                val next = text.getOrNull(index + 1)
                if (previous != null && next != null && isCjk(previous) && isCjk(next)) {
                    continue
                }
            }
            builder.append(character)
        }
        return builder.toString().trim()
    }

    fun looksLikeLine(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 2) return false
        return text.all { it in LINE_LIKE }
    }

    private fun isCjk(character: Char): Boolean =
        character.code in 0x3400..0x9FFF || character.code in 0xF900..0xFAFF
}
