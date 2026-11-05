//
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// limitations under the License.

package com.paddle.ocr.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.CellReading
import com.paddle.ocr.model.ModelConfig
import com.paddle.ocr.model.ModelSource
import com.paddle.ocr.model.OCRError
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.postprocess.BoxSorter
import com.paddle.ocr.postprocess.CellText
import com.paddle.ocr.postprocess.QuadTextCrop
import com.paddle.ocr.util.BitmapUtils
import java.io.File
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
    modelDir: File? = null,
) {
    private val ortManager = ORTSessionManager(context, engineConfig)
    private val detectionEngine: DetectionEngine
    private val recognitionEngine: RecognitionEngine
    /** 复核模型（可选）：比主识别模型更大更准，只用来给难认的格子投票。 */
    private val refereeEngine: RecognitionEngine?
    val coldLoadTimeMs: Long get() = ortManager.coldLoadTimeMs

    init {
        val refereeModel = modelDir?.let { File(it, "referee/inference.onnx") }?.takeIf { it.isFile }
        val configured = try {
            if (modelDir != null) {
                // 运行时下载的模型：直接读内部存储里的文件。
                ortManager.loadModels(
                    ModelSource.Local(File(modelDir, "det/inference.onnx")),
                    ModelSource.Local(File(modelDir, "rec/inference.onnx")),
                    refereeModel?.let { ModelSource.Local(it) },
                )
                ModelConfig.parse(File(modelDir, "rec/inference.yml"))
            } else {
                ortManager.loadModels(
                    ModelSource.Asset(context, detModelAsset),
                    ModelSource.Asset(context, recModelAsset),
                )
                ModelConfig.parse(context, recConfigAsset)
            }
        } catch (t: Throwable) {
            ortManager.release()
            throw t
        }
        detectionEngine = DetectionEngine(ortManager, config)
        recognitionEngine = RecognitionEngine(ortManager, configured.characterList)
        refereeEngine = refereeModel?.let { model ->
            val yml = File(model.parentFile, "inference.yml")
            val chars = if (yml.isFile) {
                runCatching { ModelConfig.parse(yml).characterList }.getOrNull()
            } else {
                null
            } ?: configured.characterList
            RecognitionEngine(ortManager, chars, referee = true)
        }
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

    fun recognizeCells(
        bitmap: Bitmap,
        cells: List<Rect>,
        scale: Float = 1f,
        onProgress: ((Float) -> Unit)? = null,
    ): List<CellReading> = recognizeCellsWith(bitmap, cells, scale, recognitionEngine, onProgress)

    /** 用复核模型再认一遍；没有复核模型时返回空列表。 */
    fun recognizeCellsReferee(
        bitmap: Bitmap,
        cells: List<Rect>,
        scale: Float = 1f,
        onProgress: ((Float) -> Unit)? = null,
    ): List<CellReading> {
        val engine = refereeEngine ?: return emptyList()
        return recognizeCellsWith(bitmap, cells, scale, engine, onProgress)
    }

    private fun recognizeCellsWith(
        bitmap: Bitmap,
        cells: List<Rect>,
        scale: Float,
        recEngine: RecognitionEngine,
        onProgress: ((Float) -> Unit)? = null,
    ): List<CellReading> {
        if (cells.isEmpty()) return emptyList()
        val src = BitmapUtils.bitmapToBGRMat(bitmap)
        val output = arrayOfNulls<CellReading>(cells.size)
        var pending = ArrayList<PendingCell>(CHUNK_CELLS)
        try {
            cells.forEachIndexed { index, cell ->
                val crop = cropCell(src, cell, scale)
                if (crop == null) {
                    output[index] = CellReading("", 0f, hasInk = false)
                    return@forEachIndexed
                }
                // 空白格直接跳过，避免模型在空框里“猜”出字。
                if (!hasInk(crop)) {
                    crop.release()
                    output[index] = CellReading("", 0f, hasInk = false)
                    return@forEachIndexed
                }
                val estimatedWidth = (CELL_INPUT_HEIGHT * crop.cols().toDouble() / crop.rows())
                    .toInt()
                    .coerceIn(16, 3200)
                pending.add(PendingCell(index, crop, estimatedWidth))
                // 攒够一块就先算掉：524 个格子全部留在内存里会吃掉几百 MB，
                // 低端机会直接 OOM（曾经整张表退化成文字聚类）。
                if (pending.size >= CHUNK_CELLS) {
                    runBatch(output, pending, recEngine)
                    // 逐块回调进度：格子多的表要跑十几秒，没进度条像卡死。
                    onProgress?.invoke((index + 1).toFloat() / cells.size)
                    pending = ArrayList(CHUNK_CELLS)
                }
            }

            runBatch(output, pending, recEngine)
            onProgress?.invoke(1f)
            pending = ArrayList(0)
        } finally {
            pending.forEach { runCatching { it.crop.release() } }
            src.release()
        }
        return cells.indices.map { output[it] ?: CellReading("", 0f, hasInk = false) }
    }

    private fun runBatch(
        output: Array<CellReading?>,
        cells: List<PendingCell>,
        engine: RecognitionEngine,
    ) {
        if (cells.isEmpty()) return
        try {
            val sorted = cells.sortedBy { it.width }
            var start = 0
            while (start < sorted.size) {
                var end = start
                var batchWidth = 0
                while (end < sorted.size && end - start < MAX_CELL_BATCH) {
                    val width = sorted[end].width
                    val exceedsBytes =
                        (batchWidth + width) * CELL_INPUT_HEIGHT * 3 * 4 > MAX_CELL_BATCH_BYTES
                    if (end > start && exceedsBytes) break
                    batchWidth += width
                    end++
                }
                val slice = sorted.subList(start, end)
                val texts = engine.recognize(slice.map { it.crop }).texts
                slice.forEachIndexed { offset, item ->
                    val reading = texts.getOrNull(offset)
                    output[item.index] = if (reading == null) {
                        CellReading("", 0f, hasInk = true)
                    } else {
                        CellReading(CellText.clean(reading.first), reading.second, hasInk = true)
                    }
                }
                start = end
            }
        } finally {
            cells.forEach { runCatching { it.crop.release() } }
        }
    }

    private class PendingCell(val index: Int, val crop: Mat, val width: Int)

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

/** 每次最多攒这么多裁剪图再算，控制峰值内存。 */
private const val CHUNK_CELLS = 64
