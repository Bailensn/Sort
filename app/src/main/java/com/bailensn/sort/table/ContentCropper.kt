package com.bailensn.sort.table

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 流水线第一步：把画面裁到「有内容的那块」，顺手丢掉大块无用色片。
 *
 * 手机拍/截的表格图，边上常躺着大片东西：桌面、键盘、显示器黑边、微信截图的灰底。
 * 它们在后续线检测眼里都是「可能的表格线」，是右侧垃圾列的根源。
 *
 * 判据是**暗像素占比**（Otsu 二值之后每列/每行有多少黑）：
 * 文字列大约 8%~30%，纯白边是 0%，大片深色背景（黑边框、阴影）会到 80% 以上。
 * 所以只保留占比落在 [2%, 55%] 的列/行，再取其中最长的一段连续区间。
 *
 * 保守兜底：裁完小于画面 15% 或大于 92%（基本等于没裁）时，原样返回。
 */
object ContentCropper {

    /** 分析用的最长边，再大也只是算得慢。 */
    private const val ANALYSIS_SIDE = 600.0

    /** 一列/一行里暗像素占比低于这个值：空白边。 */
    private const val MIN_INK_RATIO = 0.02

    /** 高于这个值：大片深色背景（黑边、阴影），不是文字。 */
    private const val MAX_INK_RATIO = 0.55

    /** 裁完小于画面这个比例，说明判错了，别裁。 */
    private const val MIN_KEEP_RATIO = 0.15

    /** 裁完大于这个比例，基本等于没裁。 */
    private const val MAX_KEEP_RATIO = 0.92

    fun crop(bitmap: Bitmap): Bitmap {
        if (bitmap.width < 200 || bitmap.height < 200) return bitmap
        val bounds = runCatching { computeBounds(bitmap) }.getOrNull() ?: return bitmap
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        if (width <= 0 || height <= 0) return bitmap
        val keptRatio = width.toDouble() * height / (bitmap.width.toDouble() * bitmap.height)
        if (keptRatio < MIN_KEEP_RATIO || keptRatio > MAX_KEEP_RATIO) return bitmap
        return runCatching { Bitmap.createBitmap(bitmap, bounds.left, bounds.top, width, height) }
            .getOrDefault(bitmap)
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun computeBounds(bitmap: Bitmap): Bounds {
        val rgba = Mat()
        val gray = Mat()
        val small = Mat()
        val binary = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val scale = minOf(1.0, ANALYSIS_SIDE / maxOf(gray.cols(), gray.rows()))
            Imgproc.resize(
                gray,
                small,
                Size(gray.cols() * scale, gray.rows() * scale),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )
            Imgproc.threshold(
                small,
                binary,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU,
            )

            val width = small.cols()
            val height = small.rows()
            val pixels = ByteArray(width * height)
            binary.get(0, 0, pixels)

            val columnInk = DoubleArray(width)
            val rowInk = DoubleArray(height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    if (pixels[offset + x].toInt() != 0) {
                        columnInk[x] += 1.0
                        rowInk[y] += 1.0
                    }
                }
            }
            for (x in 0 until width) columnInk[x] /= height
            for (y in 0 until height) rowInk[y] /= width

            val columnRun = longestRun(columnInk) ?: return fallback(width, height, scale)
            val rowRun = longestRun(rowInk) ?: return fallback(width, height, scale)

            // 留一点余量，避免把贴边的字切掉。
            val padX = (width * 0.01).toInt().coerceAtLeast(2)
            val padY = (height * 0.01).toInt().coerceAtLeast(2)
            val inverse = 1.0 / scale
            return Bounds(
                left = ((columnRun.first - padX).coerceAtLeast(0) * inverse).toInt(),
                top = ((rowRun.first - padY).coerceAtLeast(0) * inverse).toInt(),
                right = ((columnRun.last + 1 + padX).coerceAtMost(width) * inverse).toInt(),
                bottom = ((rowRun.last + 1 + padY).coerceAtMost(height) * inverse).toInt(),
            )
        } finally {
            rgba.release()
            gray.release()
            small.release()
            binary.release()
        }
    }

    /** 找占比落在 [MIN_INK_RATIO, MAX_INK_RATIO] 的最长连续区间。 */
    private fun longestRun(ink: DoubleArray): IntRange? {
        var bestStart = -1
        var bestEnd = -1
        var start = -1
        for (index in ink.indices) {
            val keep = ink[index] in MIN_INK_RATIO..MAX_INK_RATIO
            if (keep) {
                if (start < 0) start = index
            } else if (start >= 0) {
                if (index - 1 - start > bestEnd - bestStart) {
                    bestStart = start
                    bestEnd = index - 1
                }
                start = -1
            }
        }
        if (start >= 0 && ink.size - 1 - start > bestEnd - bestStart) {
            bestStart = start
            bestEnd = ink.size - 1
        }
        return if (bestStart >= 0) bestStart..bestEnd else null
    }

    private fun fallback(width: Int, height: Int, scale: Double): Bounds {
        val inverse = 1.0 / scale
        return Bounds(0, 0, (width * inverse).toInt(), (height * inverse).toInt())
    }
}
