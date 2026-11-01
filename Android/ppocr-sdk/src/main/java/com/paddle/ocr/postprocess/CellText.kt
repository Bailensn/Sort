//
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// limitations under the License.

package com.paddle.ocr.postprocess

object CellText {

    /** 出现在单元格两端时基本可以确定是线头或标点噪声。 */
    private const val EDGE_NOISE = "|｜丨_—–-·•.,，。:：;；'\"`~^"

    /** 线头噪声可能被识别成的“字”。 */
    private const val LINE_LIKE = "|｜丨_—–-1lI"

    fun clean(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        text = text.trim { it in EDGE_NOISE }
        if (text.isEmpty()) return ""

        // 汉字之间被识别出的空格去掉；英文单词、数字之间的空格保留。
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
