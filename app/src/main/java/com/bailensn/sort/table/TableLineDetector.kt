package com.bailensn.sort.table

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/** 一条拟合出来的直线：横线是 y = slope * x + intercept，竖线是 x = slope * y + intercept。 */
data class FittedLine(
    val slope: Float,
    val intercept: Float,
) {
    fun valueAt(t: Float): Float = slope * t + intercept
}

/**
 * 线检测结果，保留斜率信息。投影只用来找“大概在哪”，
 * 每个线带再按 x 窗口取质心做最小二乘拟合，得到真实的斜线。
 */
data class LineDetection(
    val width: Int,
    val height: Int,
    val horizontal: List<FittedLine>,
    val vertical: List<FittedLine>,
) {
    val isUsable: Boolean get() = horizontal.size >= 2 && vertical.size >= 2

    fun toGridLines(): GridLines = GridLines(
        horizontal = horizontal.map { it.valueAt(width / 2f) },
        vertical = vertical.map { it.valueAt(height / 2f) },
    )
}

object TableLineDetector {

    private const val MAX_ANALYSIS_SIDE = 1600.0
    private const val MIN_LINE_RATIO = 0.3
    private const val FIT_WINDOW = 32
    private const val MAX_SLOPE = 0.15f

    fun detect(bitmap: Bitmap): LineDetection {
        val rgba = Mat()
        val gray = Mat()
        val work = Mat()
        val binary = Mat()
        val horizontalMask = Mat()
        val verticalMask = Mat()
        val transposed = Mat()

        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

            val scale = minOf(1.0, MAX_ANALYSIS_SIDE / maxOf(gray.cols(), gray.rows()))
            if (scale < 1.0) {
                Imgproc.resize(
                    gray,
                    work,
                    Size(gray.cols() * scale, gray.rows() * scale),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
            } else {
                gray.copyTo(work)
            }

            Imgproc.adaptiveThreshold(
                work,
                binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_MEAN_C,
                Imgproc.THRESH_BINARY_INV,
                15,
                10.0,
            )

            val width = work.cols()
            val height = work.rows()
            val horizontalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(maxOf(20.0, width / 25.0), 1.0),
            )
            val verticalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(1.0, maxOf(20.0, height / 25.0)),
            )
            try {
                Imgproc.morphologyEx(binary, horizontalMask, Imgproc.MORPH_OPEN, horizontalKernel)
                Imgproc.morphologyEx(binary, verticalMask, Imgproc.MORPH_OPEN, verticalKernel)
            } finally {
                horizontalKernel.release()
                verticalKernel.release()
            }

            val horizontal = findBands(horizontalMask).mapNotNull { band ->
                fitBand(horizontalMask, band, scale)
            }
            Core.transpose(verticalMask, transposed)
            val vertical = findBands(transposed).mapNotNull { band ->
                fitBand(transposed, band, scale)
            }

            return LineDetection(
                width = bitmap.width,
                height = bitmap.height,
                horizontal = merge(horizontal, bitmap.width / 2f),
                vertical = merge(vertical, bitmap.height / 2f),
            )
        } finally {
            rgba.release()
            gray.release()
            work.release()
            binary.release()
            horizontalMask.release()
            verticalMask.release()
            transposed.release()
        }
    }

    private fun findBands(mask: Mat): List<IntRange> {
        val rows = mask.rows()
        val columns = mask.cols()
        if (rows == 0 || columns == 0) return emptyList()

        val minCount = (columns * MIN_LINE_RATIO).toInt().coerceAtLeast(1)
        val buffer = ByteArray(columns)
        val bands = mutableListOf<IntRange>()
        var start = -1

        for (row in 0..rows) {
            var hit = false
            if (row < rows) {
                mask.get(row, 0, buffer)
                var count = 0
                for (value in buffer) {
                    if (value.toInt() != 0) count++
                }
                hit = count >= minCount
            }
            if (hit && start < 0) {
                start = row
            }
            if (!hit && start >= 0) {
                bands.add(start..(row - 1))
                start = -1
            }
        }
        return bands
    }

    /** 在一条线带里按窗口取质心，最小二乘拟合出斜率。 */
    private fun fitBand(mask: Mat, band: IntRange, scale: Double): FittedLine? {
        val columns = mask.cols()
        val top = (band.first - 3).coerceAtLeast(0)
        val bottom = (band.last + 3).coerceAtMost(mask.rows() - 1)
        val height = bottom - top + 1
        if (height <= 0 || columns <= 0) return null

        val data = Array(height) { ByteArray(columns) }
        for (index in 0 until height) {
            mask.get(top + index, 0, data[index])
        }

        val points = ArrayList<Pair<Double, Double>>()
        var x = 0
        while (x < columns) {
            val end = minOf(x + FIT_WINDOW, columns)
            var sumY = 0.0
            var count = 0
            for (column in x until end) {
                for (row in 0 until height) {
                    if (data[row][column].toInt() != 0) {
                        sumY += row
                        count++
                    }
                }
            }
            if (count >= 2) {
                points.add((x + end - 1) / 2.0 to top + sumY / count)
            }
            x = end
        }
        if (points.size < 4) return null

        val meanX = points.sumOf { it.first } / points.size
        val meanY = points.sumOf { it.second } / points.size
        var sxx = 0.0
        var sxy = 0.0
        for ((pointX, pointY) in points) {
            val dx = pointX - meanX
            sxx += dx * dx
            sxy += dx * (pointY - meanY)
        }
        if (sxx < 1e-6) return null

        val slope = sxy / sxx
        if (abs(slope) > MAX_SLOPE) return null
        val intercept = meanY - slope * meanX
        return FittedLine(
            slope = slope.toFloat(),
            intercept = (intercept / scale).toFloat(),
        )
    }

    /** 去掉重复线（同一根粗线被拟合成两条），位置取平均。 */
    private fun merge(lines: List<FittedLine>, center: Float): List<FittedLine> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedBy { it.valueAt(center) }
        val merged = mutableListOf<FittedLine>()
        for (line in sorted) {
            val last = merged.lastOrNull()
            if (last != null && abs(line.valueAt(center) - last.valueAt(center)) < 6f) {
                merged[merged.size - 1] = FittedLine(
                    slope = (line.slope + last.slope) / 2f,
                    intercept = (line.intercept + last.intercept) / 2f,
                )
            } else {
                merged.add(line)
            }
        }
        return merged
    }
}
