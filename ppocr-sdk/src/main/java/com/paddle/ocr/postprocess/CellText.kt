// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.postprocess

/**
 * 单元格文本的清理规则。表格场景里识别结果常见的两类噪声：
 *
 * 1. 单元格边缘残留的表格线被识别成 `|`、`_`、`—`、`1` 之类的字符；
 * 2. 汉字之间被识别出空格（`张 三`），直接导出会让姓名对不上。
 */
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

    /**
     * 只有一两根竖线/横线（还常被认成 `1`、`l`、`I`）的输出。
     * 用于丢弃整图检测里由表格线产生的假文本框，避免把 `1` 填进空白格。
     */
    fun looksLikeLine(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 2) return false
        return text.all { it in LINE_LIKE }
    }

    private fun isCjk(character: Char): Boolean =
        character.code in 0x3400..0x9FFF || character.code in 0xF900..0xFAFF
}
