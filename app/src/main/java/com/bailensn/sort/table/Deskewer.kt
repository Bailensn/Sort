package com.bailensn.sort.table

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 手机拍出来的表格往往是斜的，先把它转正再切格/识别，精度会高很多。
 *
 * 角度估计用投影法：把二值图按候选角度旋转，统计每一行的白点数量，
 * 行与行之间跳变最尖锐的角度就是表格线最水平的那个角度。
 * 只在小角度范围（±3°）内搜索，避免把整张图转歪。
 */
object Deskewer {

    data class Result(
        val bitmap: Bitmap,
        val angleDegrees: Double,
    )

    private const val MAX_ANALYSIS_WIDTH = 800.0
    private const val MAX_ANGLE = 3.0
    private const val ANGLE_STEP = 0.25

    fun deskew(bitmap: Bitmap): Result {
        val angle = estimateAngle(bitmap)
        if (abs(angle) < 0.2) return Result(bitmap, 0.0)
        return Result(rotate(bitmap, angle), angle)
    }

    private fun estimateAngle(bitmap: Bitmap): Double {
        val rgba = Mat()
        val gray = Mat()
        val small = Mat()
        val binary = Mat()
        val rotated = Mat()
        val rowSums = Mat()

        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

            val scale = minOf(1.0, MAX_ANALYSIS_WIDTH / gray.cols())
            if (scale < 1.0) {
                Imgproc.resize(
                    gray,
                    small,
                    Size(gray.cols() * scale, gray.rows() * scale),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
            } else {
                gray.copyTo(small)
            }

            Imgproc.threshold(
                small,
                binary,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU,
            )

            val center = Point(binary.cols() / 2.0, binary.rows() / 2.0)
            val sums = IntArray(binary.rows())
            var bestAngle = 0.0
            var bestScore = -1.0
            var zeroScore = 0.0

            var angle = -MAX_ANGLE
            while (angle <= MAX_ANGLE + 1e-9) {
                val matrix = Imgproc.getRotationMatrix2D(center, angle, 1.0)
                Imgproc.warpAffine(
                    binary,
                    rotated,
                    matrix,
                    binary.size(),
                    Imgproc.INTER_NEAREST,
                    Core.BORDER_CONSTANT,
                    Scalar(0.0),
                )
                matrix.release()

                Core.reduce(rotated, rowSums, 1, Core.REDUCE_SUM, CvType.CV_32S)
                rowSums.get(0, 0, sums)

                var score = 0.0
                for (index in 1 until sums.size) {
                    val delta = (sums[index] - sums[index - 1]).toDouble()
                    score += delta * delta
                }
                if (abs(angle) < 1e-6) zeroScore = score
                if (score > bestScore) {
                    bestScore = score
                    bestAngle = angle
                }
                angle += ANGLE_STEP
            }

            // 提升不明显就认为没有倾斜，避免无谓的重采样。
            if (zeroScore > 0 && bestScore < zeroScore * 1.02) return 0.0
            return bestAngle
        } finally {
            rgba.release()
            gray.release()
            small.release()
            binary.release()
            rotated.release()
            rowSums.release()
        }
    }

    private fun rotate(bitmap: Bitmap, angleDegrees: Double): Bitmap {
        val radians = Math.toRadians(angleDegrees)
        val sin = abs(sin(radians))
        val cos = abs(cos(radians))
        val newWidth = (bitmap.width * cos + bitmap.height * sin).roundToInt()
        val newHeight = (bitmap.width * sin + bitmap.height * cos).roundToInt()

        val src = Mat()
        val dst = Mat()
        val matrix = Imgproc.getRotationMatrix2D(
            Point(bitmap.width / 2.0, bitmap.height / 2.0),
            angleDegrees,
            1.0,
        )
        try {
            Utils.bitmapToMat(bitmap, src)
            val values = DoubleArray(6)
            matrix.get(0, 0, values)
            values[2] += (newWidth - bitmap.width) / 2.0
            values[5] += (newHeight - bitmap.height) / 2.0
            matrix.put(0, 0, *values)

            Imgproc.warpAffine(
                src,
                dst,
                matrix,
                Size(newWidth.toDouble(), newHeight.toDouble()),
                Imgproc.INTER_CUBIC,
                Core.BORDER_CONSTANT,
                Scalar(255.0, 255.0, 255.0, 255.0),
            )

            val output = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dst, output)
            return output
        } finally {
            matrix.release()
            src.release()
            dst.release()
        }
    }
}
