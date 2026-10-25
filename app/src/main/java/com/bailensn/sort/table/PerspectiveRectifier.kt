package com.bailensn.sort.table

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 微矫正：用检测到的四条最外层表格线求交点，做一次透视变换，
 * 让表格在像素层面变成正矩形，后续切格和裁字都不会歪。
 *
 * 只在四角都落在画面附近、且四边形形状合理时才执行；
 * 表格被裁掉一半（缺边）时直接放弃，退回“只纠偏不透视”。
 */
object PerspectiveRectifier {

    fun rectify(bitmap: Bitmap, detection: LineDetection): Bitmap? {
        if (!detection.isUsable) return null

        val centerX = detection.width / 2f
        val centerY = detection.height / 2f
        val top = detection.horizontal.minByOrNull { it.valueAt(centerX) } ?: return null
        val bottom = detection.horizontal.maxByOrNull { it.valueAt(centerX) } ?: return null
        val left = detection.vertical.minByOrNull { it.valueAt(centerY) } ?: return null
        val right = detection.vertical.maxByOrNull { it.valueAt(centerY) } ?: return null

        val topLeft = intersect(top, left) ?: return null
        val topRight = intersect(top, right) ?: return null
        val bottomLeft = intersect(bottom, left) ?: return null
        val bottomRight = intersect(bottom, right) ?: return null

        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val marginX = width * 0.08f
        val marginY = height * 0.08f
        val corners = listOf(topLeft, topRight, bottomRight, bottomLeft)
        if (corners.any {
                it.x < -marginX || it.x > width + marginX || it.y < -marginY || it.y > height + marginY
            }
        ) {
            return null
        }

        // 最外圈那条线没被检测到时，画面会被裁掉一整列（比如最左边的班级列）。
        // 这里在左右两侧找一下表格外面还有没有内容，有就把四边形扩到内容边界。
        val expanded = expandToInk(bitmap, topLeft, topRight, bottomLeft, bottomRight)

        val targetWidth = ((distance(expanded[0], expanded[1]) + distance(expanded[2], expanded[3])) / 2f)
            .roundToInt()
        val targetHeight = ((distance(expanded[0], expanded[3]) + distance(expanded[1], expanded[2])) / 2f)
            .roundToInt()
        if (targetWidth < 200 || targetHeight < 150 || targetWidth > 4000 || targetHeight > 4000) {
            return null
        }

        // 角度太歪说明四条线不是同一张表的外框，放弃。
        if (!isRightAngle(expanded[0], expanded[1], expanded[3])) return null

        val src = Mat()
        val warped = Mat()
        val srcPoints = MatOfPoint2f(
            Point(expanded[0].x, expanded[0].y),
            Point(expanded[1].x, expanded[1].y),
            Point(expanded[2].x, expanded[2].y),
            Point(expanded[3].x, expanded[3].y),
        )
        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(targetWidth.toDouble(), 0.0),
            Point(targetWidth.toDouble(), targetHeight.toDouble()),
            Point(0.0, targetHeight.toDouble()),
        )

        try {
            Utils.bitmapToMat(bitmap, src)
            val matrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            try {
                Imgproc.warpPerspective(
                    src,
                    warped,
                    matrix,
                    Size(targetWidth.toDouble(), targetHeight.toDouble()),
                    Imgproc.INTER_CUBIC,
                    Core.BORDER_REPLICATE,
                )
            } finally {
                matrix.release()
            }

            val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, output)
            return output
        } finally {
            srcPoints.release()
            dstPoints.release()
            src.release()
            warped.release()
        }
    }

    /**
     * 左右两侧如果还有墨迹（说明最外圈的竖线没检测到、或者表格本身超出检测框），
     * 就把四边形的左右边扩到内容边界；上下不扩，避免把标题、页码带进来。
     *
     * 返回 [左上, 右上, 右下, 左下]。
     */
    private fun expandToInk(
        bitmap: Bitmap,
        topLeft: Point,
        topRight: Point,
        bottomRight: Point,
        bottomLeft: Point,
    ): List<Point> {
        val rgba = Mat()
        val gray = Mat()
        val binary = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
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
            val top = minOf(topLeft.y, topRight.y).toInt().coerceIn(0, height - 1)
            val bottom = maxOf(bottomLeft.y, bottomRight.y).toInt().coerceIn(top + 1, height)
            val left = minOf(topLeft.x, bottomLeft.x).toInt().coerceIn(0, width - 1)
            val right = maxOf(topRight.x, bottomRight.x).toInt().coerceIn(left + 1, width)

            val counts = IntArray(width)
            val row = ByteArray(width)
            val band = bottom - top
            for (y in top until bottom) {
                binary.get(y, 0, row)
                for (x in 0 until width) {
                    if (row[x].toInt() != 0) counts[x]++
                }
            }
            val minInk = maxOf(3, (band * 0.01).toInt())

            var contentLeft = left
            for (x in 0 until left) {
                if (counts[x] >= minInk) {
                    contentLeft = x
                    break
                }
            }
            var contentRight = right
            for (x in width - 1 downTo right) {
                if (counts[x] >= minInk) {
                    contentRight = x + 1
                    break
                }
            }

            val pad = maxOf(4, width / 200)
            val expandLeft = (left - contentLeft) > pad
            val expandRight = (contentRight - right) > pad
            // 扩得太多说明框外是别的东西（标题、别的表格），放弃扩展。
            if (expandLeft && (left - contentLeft) > width * 0.3) return listOf(topLeft, topRight, bottomRight, bottomLeft)
            if (expandRight && (contentRight - right) > width * 0.3) return listOf(topLeft, topRight, bottomRight, bottomLeft)

            val newLeft = if (expandLeft) (contentLeft - pad).coerceAtLeast(0).toDouble() else null
            val newRight = if (expandRight) (contentRight + pad).coerceAtMost(width).toDouble() else null

            val tl = if (newLeft != null) Point(newLeft, topLeft.y) else topLeft
            val bl = if (newLeft != null) Point(newLeft, bottomLeft.y) else bottomLeft
            val tr = if (newRight != null) Point(newRight, topRight.y) else topRight
            val br = if (newRight != null) Point(newRight, bottomRight.y) else bottomRight
            return listOf(tl, tr, br, bl)
        } catch (t: Throwable) {
            return listOf(topLeft, topRight, bottomRight, bottomLeft)
        } finally {
            rgba.release()
            gray.release()
            binary.release()
        }
    }

    private fun intersect(horizontal: FittedLine, vertical: FittedLine): Point? {
        val denominator = 1.0 - vertical.slope * horizontal.slope
        if (abs(denominator) < 1e-4) return null
        val x = (vertical.slope * horizontal.intercept + vertical.intercept) / denominator
        val y = horizontal.slope * x + horizontal.intercept
        if (!x.isFinite() || !y.isFinite()) return null
        return Point(x, y)
    }

    private fun distance(a: Point, b: Point): Float =
        hypot((a.x - b.x).toFloat(), (a.y - b.y).toFloat())

    private fun isRightAngle(topLeft: Point, topRight: Point, bottomLeft: Point): Boolean {
        val ax = topRight.x - topLeft.x
        val ay = topRight.y - topLeft.y
        val bx = bottomLeft.x - topLeft.x
        val by = bottomLeft.y - topLeft.y
        val lengthA = hypot(ax, ay)
        val lengthB = hypot(bx, by)
        if (lengthA < 1e-3 || lengthB < 1e-3) return false
        val cosine = abs(ax * bx + ay * by) / (lengthA * lengthB)
        return cosine < 0.25
    }
}
