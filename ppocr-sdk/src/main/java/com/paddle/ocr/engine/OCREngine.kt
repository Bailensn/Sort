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

package com.paddle.ocr.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.CellReading
import com.paddle.ocr.model.ModelConfig
import com.paddle.ocr.model.OCRError
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.postprocess.BoxSorter
import com.paddle.ocr.postprocess.CellText
import com.paddle.ocr.postprocess.QuadTextCrop
import com.paddle.ocr.util.BitmapUtils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class OCREngine(
    context: Context,
    private val config: PaddleOCRConfig,
    engineConfig: EngineConfig,
    detModelAsset: String = "models/det/inference.onnx",
    recModelAsset: String = "models/rec/inference.onnx",
    recConfigAsset: String = "models/rec/inference.yml",
) {
    private val ortManager = ORTSessionManager(context, engineConfig)
    private val detectionEngine: DetectionEngine
    private val recognitionEngine: RecognitionEngine
    val coldLoadTimeMs: Long get() = ortManager.coldLoadTimeMs

    init {
        val configured = try {
            ortManager.loadModels(detModelAsset, recModelAsset)
            val recModelConfig = ModelConfig.parse(context, recConfigAsset)
            recModelConfig
        } catch (t: Throwable) {
            ortManager.release()
            throw t
        }
        detectionEngine = DetectionEngine(ortManager, config)
        recognitionEngine = RecognitionEngine(ortManager, configured.characterList)
    }

    fun run(bitmap: Bitmap): OCREngineResult {
        val srcMat = BitmapUtils.bitmapToBGRMat(bitmap)
        return runWithOwnedMat(srcMat)
    }

    fun run(imageBytes: ByteArray): OCREngineResult {
        val srcMat = BitmapUtils.imdecodeBGR(imageBytes)
        if (srcMat.empty()) {
            srcMat.release()
            throw OCRError.InvalidImage()
        }
        return runWithOwnedMat(srcMat)
    }

    private fun runWithOwnedMat(srcMat: org.opencv.core.Mat): OCREngineResult {
        return try {
            run(srcMat)
        } finally {
            srcMat.release()
        }
    }

    private fun run(srcMat: org.opencv.core.Mat): OCREngineResult {
        val totalStart = System.currentTimeMillis()
        val detResult = detectionEngine.detect(srcMat)
        val boxes = detResult.boxes

        if (boxes.isEmpty()) {
            val elapsed = System.currentTimeMillis() - totalStart
            return OCREngineResult(
                results = emptyList(),
                detectionTimeMs = detResult.timeMs,
                recognitionTimeMs = 0,
                totalTimeMs = elapsed,
                lineCount = 0,
                detPreprocessMs = detResult.preprocessMs,
                detInferenceMs = detResult.inferenceMs,
                detPostprocessMs = detResult.postprocessMs,
                detInputShape = detResult.inputShape,
                coldLoadTimeMs = ortManager.coldLoadTimeMs,
            )
        }

        // 2. Sort boxes
        val sortedBoxes = BoxSorter.sortInReadingOrder(boxes)

        // 3. Crop and recognize text regions
        var totalRecPreMs = 0L
        var totalRecInfMs = 0L
        var totalRecPostMs = 0L
        var totalRecMs = 0L
        val allResults = mutableListOf<OCRResult>()
        val recInputShapes = mutableListOf<List<Int>>()
        val perLineRecMs = mutableListOf<Long>()
        val batchSize = config.recBatchSize.coerceAtLeast(1)

        var i = 0
        while (i < sortedBoxes.size) {
            val batchCrops = mutableListOf<org.opencv.core.Mat>()
            val batchBoxIndices = mutableListOf<Int>()
            var next = i
            while (next < sortedBoxes.size && batchCrops.size < batchSize) {
                val crop = QuadTextCrop.crop(srcMat, sortedBoxes[next])
                if (crop.rows() > 0 && crop.cols() > 0) {
                    batchCrops.add(crop)
                    batchBoxIndices.add(next)
                } else {
                    crop.release()
                }
                next++
            }

            try {
                if (batchCrops.isNotEmpty()) {
                    val batchResult = recognitionEngine.recognize(batchCrops)
                    totalRecPreMs += batchResult.preprocessMs
                    totalRecInfMs += batchResult.inferenceMs
                    totalRecPostMs += batchResult.postprocessMs
                    totalRecMs += batchResult.timeMs
                    recInputShapes.add(batchResult.inputShape)
                    if (batchSize == 1) {
                        perLineRecMs.add(batchResult.timeMs)
                    }

                    for (j in batchResult.texts.indices) {
                        val boxIdx = batchBoxIndices[j]
                        val (text, confidence) = batchResult.texts[j]
                        if (confidence >= config.recScoreThresh) {
                            allResults.add(
                                OCRResult(
                                    box = sortedBoxes[boxIdx],
                                    text = text,
                                    confidence = confidence,
                                )
                            )
                        }
                    }
                }
            } finally {
                batchCrops.forEach { it.release() }
            }
            i = next
        }

        val totalElapsed = System.currentTimeMillis() - totalStart
        val pipelineOverhead = totalElapsed - detResult.timeMs - totalRecMs

        return OCREngineResult(
            results = allResults,
            detectionTimeMs = detResult.timeMs,
            recognitionTimeMs = totalRecMs,
            totalTimeMs = totalElapsed,
            lineCount = allResults.size,
            detPreprocessMs = detResult.preprocessMs,
            detInferenceMs = detResult.inferenceMs,
            detPostprocessMs = detResult.postprocessMs,
            recPreprocessMs = totalRecPreMs,
            recInferenceMs = totalRecInfMs,
            recPostprocessMs = totalRecPostMs,
            pipelineOverheadMs = pipelineOverhead,
            coldLoadTimeMs = ortManager.coldLoadTimeMs,
            detInputShape = detResult.inputShape,
            recInputShapes = recInputShapes,
            perLineRecMs = perLineRecMs,
        )
    }

    /**
     * 逐格识别：按给定矩形裁图，识别每个单元格里的文字。
     * 表格场景比整图检测稳，因为不会出现跨列合并的检测框。
     * 返回顺序与入参一致；[CellReading.hasInk] 区分「真空白」和「有字没认出来」。
     */
    fun recognizeCells(bitmap: Bitmap, cells: List<Rect>, scale: Float = 1f): List<CellReading> {
        if (cells.isEmpty()) return emptyList()
        val src = BitmapUtils.bitmapToBGRMat(bitmap)
        val output = ArrayList<CellReading>(cells.size)
        var batch = ArrayList<Mat>()
        var batchWidth = 0

        fun flush() {
            if (batch.isEmpty()) return
            try {
                output.addAll(
                    recognitionEngine.recognize(batch).texts.map { (text, confidence) ->
                        CellReading(CellText.clean(text), confidence, hasInk = true)
                    },
                )
            } finally {
                batch.forEach { it.release() }
            }
            batch = ArrayList()
            batchWidth = 0
        }

        try {
            for (cell in cells) {
                val crop = cropCell(src, cell, scale)
                if (crop == null) {
                    output.add(CellReading("", 0f, hasInk = false))
                    continue
                }
                // 空白格直接跳过，避免模型在空框里“猜”出字。
                if (!hasInk(crop)) {
                    crop.release()
                    output.add(CellReading("", 0f, hasInk = false))
                    continue
                }
                val estimatedWidth = (CELL_INPUT_HEIGHT * crop.cols().toDouble() / crop.rows())
                    .toInt()
                    .coerceIn(16, 3200)
                val tooLarge = (batchWidth + estimatedWidth) * CELL_INPUT_HEIGHT * 3 * 4 > MAX_CELL_BATCH_BYTES
                if (batch.isNotEmpty() && (batch.size >= MAX_CELL_BATCH || tooLarge)) {
                    flush()
                }
                batch.add(crop)
                batchWidth += estimatedWidth
            }
            flush()
        } finally {
            batch.forEach { it.release() }
            src.release()
        }
        return output
    }

    /**
     * 裁出单元格内部，去掉边框，再补白边，识别模型对留白更稳。
     *
     * 小字的格子（高度不足 [SMALL_TEXT_HEIGHT] 像素）先放大再送模型：
     * 成绩单里「缺考」这类小字、或者缩得很小的备注，直接喂原尺寸会掉字。
     */
    private fun cropCell(src: Mat, cell: Rect, scale: Float = 1f): Mat? {
        if (cell.width() <= 2 || cell.height() <= 2) return null
        val inset = maxOf(2, minOf(cell.width(), cell.height()) / 40)
        val left = (cell.left + inset).coerceIn(0, src.cols() - 1)
        val top = (cell.top + inset).coerceIn(0, src.rows() - 1)
        val right = (cell.right - inset).coerceIn(left + 1, src.cols())
        val bottom = (cell.bottom - inset).coerceIn(top + 1, src.rows())
        if (right - left < 3 || bottom - top < 3) return null

        val roi = Mat(src, org.opencv.core.Rect(left, top, right - left, bottom - top))
        var scaled: Mat? = null
        return try {
            // 低置信度格子会带着 scale > 1 再识别一次，这时无论格子大小都放大。
            val targetFactor = if (scale > 1f) {
                scale.toDouble()
            } else if (roi.rows() < SMALL_TEXT_HEIGHT) {
                (SMALL_TEXT_HEIGHT.toDouble() / roi.rows()).coerceIn(1.5, 3.0)
            } else {
                1.0
            }
            val source = if (targetFactor > 1.0) {
                val factor = targetFactor.coerceAtMost(4.0)
                Mat().also {
                    Imgproc.resize(
                        roi,
                        it,
                        Size(roi.cols() * factor, roi.rows() * factor),
                        0.0,
                        0.0,
                        Imgproc.INTER_CUBIC,
                    )
                }.also { scaled = it }
            } else {
                roi
            }
            val padding = maxOf(4, source.rows() / 8)
            Mat().also { padded ->
                Core.copyMakeBorder(
                    source,
                    padded,
                    padding,
                    padding,
                    padding,
                    padding,
                    Core.BORDER_CONSTANT,
                    Scalar(255.0, 255.0, 255.0),
                )
            }
        } finally {
            scaled?.release()
            roi.release()
        }
    }

    private companion object {
        /** 低于这个高度的小字先放大再识别。 */
        const val SMALL_TEXT_HEIGHT = 28
    }

    /** 粗判单元格里有没有墨迹；空白格不送模型，减少误识别。 */
    private fun hasInk(crop: Mat): Boolean {
        val gray = Mat()
        val binary = Mat()
        return try {
            Imgproc.cvtColor(crop, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.threshold(
                gray,
                binary,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU,
            )
            val ink = Core.countNonZero(binary)
            val total = binary.rows().toLong() * binary.cols()
            total > 0 && ink.toDouble() / total > 0.002
        } finally {
            gray.release()
            binary.release()
        }
    }

    fun release() {
        ortManager.release()
    }
}

private const val CELL_INPUT_HEIGHT = 48
private const val MAX_CELL_BATCH = 16
private const val MAX_CELL_BATCH_BYTES = 24 * 1024 * 1024
