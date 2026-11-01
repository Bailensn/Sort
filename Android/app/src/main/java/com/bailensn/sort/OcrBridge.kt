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

    /** cells 按 left/top/right/bottom 顺序平铺成一个 IntArray。 */
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
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val out = ByteArray(width * height * 3)
        var index = 0
        for (pixel in argb) {
            out[index++] = (pixel and 0xFF).toByte()
            out[index++] = ((pixel shr 8) and 0xFF).toByte()
            out[index++] = ((pixel shr 16) and 0xFF).toByte()
        }
        return out
    }

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
