package com.bailensn.sort.table

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/** 估表格线有多粗：裁格时按它往内缩，线头才不会残留成「1」。 */
object LineThickness {

    private const val RADIUS = 3
    private const val SAMPLES = 8
    private const val FALLBACK = 2

    fun estimate(bitmap: Bitmap, lines: GridLines): Int = runCatching {
        val rgba = Mat()
        val gray = Mat()
        val lighting = Mat()
        val binary = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            // 和线检测同一套光照归一化，暗处的线才不会被当成「这里没画线」。
            GrayPreprocessor.flatten(gray, lighting)
            Imgproc.adaptiveThreshold(
                gray,
                binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_MEAN_C,
                Imgproc.THRESH_BINARY_INV,
                15,
                10.0,
            )
            val width = binary.cols()
            val height = binary.rows()

            fun dark(x: Int, y: Int): Boolean {
                if (x < 0 || y < 0 || x >= width || y >= height) return false
                return binary.get(y, x)?.getOrNull(0)?.let { it > 127.0 } ?: false
            }

            val runs = mutableListOf<Int>()
            lines.horizontal.forEach { line ->
                val base = line.roundToInt()
                for (step in 1..SAMPLES) {
                    val x = width * step / (SAMPLES + 1)
                    var run = 0
                    for (dy in -RADIUS..RADIUS) if (dark(x, base + dy)) run++
                    if (run > 0) runs += run
                }
            }
            lines.vertical.forEach { line ->
                val base = line.roundToInt()
                for (step in 1..SAMPLES) {
                    val y = height * step / (SAMPLES + 1)
                    var run = 0
                    for (dx in -RADIUS..RADIUS) if (dark(base + dx, y)) run++
                    if (run > 0) runs += run
                }
            }
            if (runs.isEmpty()) FALLBACK else runs.sum() / runs.size
        } finally {
            rgba.release()
            gray.release()
            lighting.release()
            binary.release()
        }
    }.getOrDefault(FALLBACK)
}
