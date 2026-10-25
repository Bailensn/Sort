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

package com.paddle.ocr.model

/**
 * 一个单元格的识别结果。
 *
 * [hasInk] 是在裁图之后、送识别模型之前用 OTSU 统计出来的「这一格里到底有没有墨迹」。
 * 它用来区分两种「空字符串」：
 *
 * - 真的空白格（选科没选的科目、缺考没有标记的格子）——不能被别处的文字补上；
 * - 有墨迹但没认出来的格子——允许用整图检测的结果回填。
 */
data class CellReading(
    val text: String,
    val confidence: Float,
    val hasInk: Boolean,
)
