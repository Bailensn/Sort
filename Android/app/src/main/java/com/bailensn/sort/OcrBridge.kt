package com.bailensn.sort

import android.graphics.Bitmap
import android.graphics.PointF

/** 一个识别框：四个角按左上、右上、右下、左下，坐标是原图像素。 */
data class OcrLine(
    val points: List<PointF>,
    val text: String,
    val confidence: Float,
)

/** 单元格识别结果；hasInk=false 表示空白格，没送模型。 */
data class CellReading(
    val text: String,
    val confidence: Float,
    val hasInk: Boolean,
)

/** 流水线阶段回调：stage 对应 C ABI 的 engine_table_stage，fraction 是 0..1。 */
fun interface TableProgress {
    fun onStage(stage: Int, fraction: Float)
}

/** 一条自检明细，对应 C ABI 的 engine_table_note_kind。 */
data class TableNote(val kind: Int, val a: Int, val b: Int)

/** 整表分析结果：行列数据、表头、自检明细、统计口径，一次调用全拿到。 */
data class TableAnalysis(
    val rows: List<List<String>>,
    val headerNames: List<String>,
    val headerRoles: List<Int>,
    val notes: List<TableNote> = emptyList(),
    val suspiciousRows: List<Int> = emptyList(),
    val byLines: Boolean = true,
    val rectified: Boolean = false,
    val headerFromImage: Boolean = false,
    val horizontalLines: Int = 0,
    val verticalLines: Int = 0,
    val cellCount: Int = 0,
    val correctedCells: Int = 0,
    val lowConfidenceCells: Int = 0,
    val averageConfidence: Float = 0f,
    val confidenceMin: Float = 0f,
    val confidenceMax: Float = 0f,
    val deskewDegrees: Double = 0.0,
    val elapsedMs: Long = 0L,
    val cellMs: Long = 0L,
    val detectMs: Long = 0L,
    val overlay: Bitmap? = null,
    val enhanced: Bitmap? = null,
)

/**
 * OCR 的 Kotlin 入口：只负责把 Bitmap 转成 BGR 缓冲，其余全在 C++。
 */
class OcrBridge(
    detModelPath: String,
    recModelPath: String,
    charsetPath: String,
) : AutoCloseable {

    private var handle: Long

    init {
        // 先加载库再建句柄：别处若也 loadLibrary 过，重复加载是幂等的。
        System.loadLibrary("main")
        handle = nativeOcrCreate(detModelPath, recModelPath, charsetPath)
    }

    val isReady: Boolean get() = handle != 0L

    /** create 失败时的错误码，见 engine_errors.h / engine_ocr_capi.h。 */
    val lastError: Int get() = nativeOcrLastError()

    fun recognize(bitmap: Bitmap): List<OcrLine> {
        if (handle == 0L) return emptyList()
        val pixels = toBgr(bitmap)
        val triple = nativeOcrRecognize(handle, pixels, bitmap.width, bitmap.height, 1) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val texts = triple[0] as Array<String>
        val boxes = triple[1] as FloatArray
        val confidences = triple[2] as FloatArray
        return texts.indices.map { i ->
            val base = i * 8
            OcrLine(
                points = listOf(
                    PointF(boxes[base], boxes[base + 1]),
                    PointF(boxes[base + 2], boxes[base + 3]),
                    PointF(boxes[base + 4], boxes[base + 5]),
                    PointF(boxes[base + 6], boxes[base + 7]),
                ),
                text = texts[i],
                confidence = confidences[i],
            )
        }
    }

    /** 整表分析：裁切、纠偏、找线、切格、多遍识别投票、清洗、表头、自检，一次调用全拿到。 */
    fun analyzeTable(bitmap: Bitmap, progress: TableProgress? = null): TableAnalysis? {
        if (handle == 0L) return null
        val pixels = toBgr(bitmap)
        val parts = nativeTableAnalyze(handle, pixels, bitmap.width, bitmap.height, 1, progress)
            ?: return null

        @Suppress("UNCHECKED_CAST")
        val rows = (parts[0] as? Array<Array<String>>)?.map { it.toList() } ?: return null
        @Suppress("UNCHECKED_CAST")
        val names = (parts[1] as? Array<String>)?.toList().orEmpty()
        val roles = (parts[2] as? IntArray)?.toList().orEmpty()
        val notes = (parts[3] as? IntArray)?.let { flat ->
            (0 until flat.size / 3).map { index ->
                TableNote(flat[index * 3], flat[index * 3 + 1], flat[index * 3 + 2])
            }
        }.orEmpty()
        val suspicious = (parts[4] as? IntArray)?.toList().orEmpty()
        val ints = parts[5] as? IntArray ?: IntArray(0)
        val floats = parts[6] as? FloatArray ?: FloatArray(0)
        val times = parts[7] as? DoubleArray ?: DoubleArray(0)

        return TableAnalysis(
            rows = rows,
            headerNames = names,
            headerRoles = roles,
            notes = notes,
            suspiciousRows = suspicious,
            byLines = (ints.getOrNull(0) ?: 1) != 0,
            rectified = ints.getOrNull(1) != 0,
            horizontalLines = ints.getOrNull(2) ?: 0,
            verticalLines = ints.getOrNull(3) ?: 0,
            cellCount = ints.getOrNull(4) ?: 0,
            correctedCells = ints.getOrNull(5) ?: 0,
            lowConfidenceCells = ints.getOrNull(7) ?: 0,
            headerFromImage = ints.getOrNull(8) != 0,
            averageConfidence = floats.getOrNull(0) ?: 0f,
            confidenceMin = floats.getOrNull(1) ?: 0f,
            confidenceMax = floats.getOrNull(2) ?: 0f,
            deskewDegrees = (floats.getOrNull(3) ?: 0f).toDouble(),
            elapsedMs = (times.getOrNull(0) ?: 0.0).toLong(),
            cellMs = (times.getOrNull(1) ?: 0.0).toLong(),
            detectMs = (times.getOrNull(2) ?: 0.0).toLong(),
            // 每张图用完就把 JNI 那侧的像素数组放掉，三张大图同时在堆里容易 OOM。
            overlay = imageOf(parts.getOrNull(9)).also { parts[9] = null },
            enhanced = imageOf(parts.getOrNull(10)).also { parts[10] = null },
        )
    }

    /** JNI 把图交成 [ARGB 像素, [宽, 高]] 两段，避免 Kotlin 侧逐像素转换。 */
    private fun imageOf(part: Any?): Bitmap? {
        val pair = part as? Array<*> ?: return null
        val pixels = pair.getOrNull(0) as? IntArray ?: return null
        val size = pair.getOrNull(1) as? IntArray ?: return null
        val width = size.getOrNull(0) ?: return null
        val height = size.getOrNull(1) ?: return null
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        return runCatching {
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    /** 挂上复核模型（PP-OCRv5 server rec）；只有 rec，没有 det。 */
    fun setReferee(recModelPath: String, charsetPath: String): Boolean =
        nativeOcrSetReferee(handle, recModelPath, charsetPath) == 0

    /** 挂上第二个主模型（换一代模型当另一路投票）；同样只有 rec。 */
    fun setAlternate(recModelPath: String, charsetPath: String): Boolean =
        nativeOcrSetAlternate(handle, recModelPath, charsetPath) == 0

    /** 用复核模型跑同一批格子，给有争议的格子投票。 */
    fun recognizeCellsReferee(bitmap: Bitmap, cells: IntArray, scale: Float = 1f): List<CellReading> {
        if (handle == 0L || cells.isEmpty()) return emptyList()
        val pixels = toBgr(bitmap)
        val triple = nativeOcrRecognizeCellsRef(
            handle, pixels, bitmap.width, bitmap.height, 1, cells, scale,
        ) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val texts = triple[0] as Array<String>
        val confidences = triple[1] as FloatArray
        val inks = triple[2] as ByteArray
        return texts.indices.map { i ->
            CellReading(texts[i], confidences[i], inks[i].toInt() != 0)
        }
    }

    /** 第二主模型跑同一批格子。 */
    fun recognizeCellsAlt(bitmap: Bitmap, cells: IntArray, scale: Float = 1f): List<CellReading> {
        if (handle == 0L || cells.isEmpty()) return emptyList()
        val pixels = toBgr(bitmap)
        val triple = nativeOcrRecognizeCellsAlt(
            handle, pixels, bitmap.width, bitmap.height, 1, cells, scale,
        ) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val texts = triple[0] as Array<String>
        val confidences = triple[1] as FloatArray
        val inks = triple[2] as ByteArray
        return texts.indices.map { i ->
            CellReading(texts[i], confidences[i], inks[i].toInt() != 0)
        }
    }

    fun recognizeCells(bitmap: Bitmap, cells: IntArray, scale: Float = 1f): List<CellReading> {
        if (handle == 0L || cells.isEmpty()) return emptyList()
        val pixels = toBgr(bitmap)
        val triple = nativeOcrRecognizeCells(
            handle, pixels, bitmap.width, bitmap.height, 1, cells, scale,
        ) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val texts = triple[0] as Array<String>
        val confidences = triple[1] as FloatArray
        val inks = triple[2] as ByteArray
        return texts.indices.map { i ->
            CellReading(texts[i], confidences[i], inks[i].toInt() != 0)
        }
    }

    override fun close() {
        if (handle != 0L) {
            nativeOcrDestroy(handle)
            handle = 0L
        }
    }

    /** ARGB_8888 → BGR 三通道，和 C++ 侧的 isBgr=1 对应。 */
    private fun toBgr(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val out = ByteArray(width * height * 3)
        var index = 0
        // 分行取像素：整图 IntArray 在 24 MP 上就是 96 MB，和位图叠一起会 OOM
        val band = 128
        val argb = IntArray(width * band)
        var y = 0
        while (y < height) {
            val rows = minOf(band, height - y)
            bitmap.getPixels(argb, 0, width, 0, y, width, rows)
            var offset = 0
            repeat(rows * width) {
                val pixel = argb[offset++]
                out[index++] = (pixel and 0xFF).toByte()
                out[index++] = ((pixel shr 8) and 0xFF).toByte()
                out[index++] = ((pixel shr 16) and 0xFF).toByte()
            }
            y += rows
        }
        return out
    }

    private external fun nativeTableAnalyze(
        handle: Long,
        pixels: ByteArray,
        width: Int,
        height: Int,
        isBgr: Int,
        progress: TableProgress?,
    ): Array<Any?>?

    private external fun nativeOcrLastError(): Int

    private external fun nativeOcrSetReferee(handle: Long, rec: String, charset: String): Int

    private external fun nativeOcrSetAlternate(handle: Long, rec: String, charset: String): Int

    private external fun nativeOcrRecognizeCellsAlt(
        handle: Long,
        pixels: ByteArray,
        width: Int,
        height: Int,
        isBgr: Int,
        cells: IntArray,
        scale: Float,
    ): Array<Any>?

    private external fun nativeOcrRecognizeCellsRef(
        handle: Long,
        pixels: ByteArray,
        width: Int,
        height: Int,
        isBgr: Int,
        cells: IntArray,
        scale: Float,
    ): Array<Any>?

    private external fun nativeOcrCreate(det: String, rec: String, charset: String): Long
    private external fun nativeOcrDestroy(handle: Long)
    private external fun nativeOcrRecognize(
        handle: Long, pixels: ByteArray, width: Int, height: Int, isBgr: Int,
    ): Array<Any>?

    private external fun nativeOcrRecognizeCells(
        handle: Long, pixels: ByteArray, width: Int, height: Int, isBgr: Int,
        cells: IntArray, scale: Float,
    ): Array<Any>?
}
