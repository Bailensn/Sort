package com.bailensn.sort.table

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * 在二值图上判断一条边界上到底有没有画线。
 *
 * 做法很直白：沿这条边界采样几十个点，每个点看左右（或上下）[RADIUS] 像素内有没有黑像素，
 * 命中率过半才算“线画到了这里”。没画线的那一段就说明相邻两格其实是合并单元格。
 *
 * 判定偏保守（[MIN_HIT_RATIO] 取 0.55）：宁可把合并格拆开，也不要把两个独立格粘成一个。
 */
class PixelBorderProbe private constructor(
    private val binary: Mat,
    private val height: Int,
    private val width: Int,
) : BorderProbe {

    private var verticalThicknessSum = 0
    private var verticalThicknessCount = 0
    private var horizontalThicknessSum = 0
    private var horizontalThicknessCount = 0

    /** 线宽估计（像素）。裁单元格时按它往内缩，避免把表格线带进识别图里。 */
    val maxThickness: Int
        get() {
            val vertical = if (verticalThicknessCount == 0) 0 else verticalThicknessSum / verticalThicknessCount
            val horizontal = if (horizontalThicknessCount == 0) 0 else horizontalThicknessSum / horizontalThicknessCount
            return maxOf(vertical, horizontal)
        }

    override fun hasVerticalBorder(x: Int, top: Int, bottom: Int): Boolean {
        val from = top.coerceIn(0, height - 1)
        val to = bottom.coerceIn(0, height - 1)
        if (to - from < MIN_SAMPLES) return true
        val step = ((to - from) / MAX_SAMPLES).coerceAtLeast(1)
        var hits = 0
        var samples = 0
        var y = from
        while (y <= to) {
            var dark = false
            var run = 0
            var dx = -RADIUS
            while (dx <= RADIUS) {
                if (isDark(x + dx, y)) {
                    dark = true
                    run++
                }
                dx++
            }
            if (dark) {
                hits++
                verticalThicknessSum += run
                verticalThicknessCount++
            }
            samples++
            y += step
        }
        return samples == 0 || hits.toFloat() / samples >= MIN_HIT_RATIO
    }

    override fun hasHorizontalBorder(y: Int, left: Int, right: Int): Boolean {
        val from = left.coerceIn(0, width - 1)
        val to = right.coerceIn(0, width - 1)
        if (to - from < MIN_SAMPLES) return true
        val step = ((to - from) / MAX_SAMPLES).coerceAtLeast(1)
        var hits = 0
        var samples = 0
        var x = from
        while (x <= to) {
            var dark = false
            var run = 0
            var dy = -RADIUS
            while (dy <= RADIUS) {
                if (isDark(x, y + dy)) {
                    dark = true
                    run++
                }
                dy++
            }
            if (dark) {
                hits++
                horizontalThicknessSum += run
                horizontalThicknessCount++
            }
            samples++
            x += step
        }
        return samples == 0 || hits.toFloat() / samples >= MIN_HIT_RATIO
    }

    private fun isDark(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= width || y >= height) return false
        return binary.get(y, x)?.getOrNull(0)?.let { it > 127.0 } ?: false
    }

    /** 探针内部持有一个全尺寸二值图，用完必须释放。 */
    fun release() {
        binary.release()
    }

    companion object {
        private const val RADIUS = 3
        private const val MIN_SAMPLES = 6
        private const val MAX_SAMPLES = 64
        private const val MIN_HIT_RATIO = 0.55f

        fun from(bitmap: Bitmap): PixelBorderProbe? = runCatching {
            val rgba = Mat()
            val gray = Mat()
            val lighting = Mat()
            try {
                Utils.bitmapToMat(bitmap, rgba)
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                // 和线检测同一套光照归一化，否则暗处的线会被判成「这里没画线」。
                GrayPreprocessor.flatten(gray, lighting)
                val binary = Mat()
                Imgproc.adaptiveThreshold(
                    gray,
                    binary,
                    255.0,
                    Imgproc.ADAPTIVE_THRESH_MEAN_C,
                    Imgproc.THRESH_BINARY_INV,
                    15,
                    10.0,
                )
                PixelBorderProbe(binary, binary.rows(), binary.cols())
            } finally {
                rgba.release()
                gray.release()
                lighting.release()
            }
        }.getOrNull()
    }
}
