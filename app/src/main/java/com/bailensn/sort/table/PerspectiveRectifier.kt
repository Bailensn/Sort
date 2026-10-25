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

        val targetWidth = ((distance(topLeft, topRight) + distance(bottomLeft, bottomRight)) / 2f)
            .roundToInt()
        val targetHeight = ((distance(topLeft, bottomLeft) + distance(topRight, bottomRight)) / 2f)
            .roundToInt()
        if (targetWidth < 200 || targetHeight < 150 || targetWidth > 4000 || targetHeight > 4000) {
            return null
        }

        // 角度太歪说明四条线不是同一张表的外框，放弃。
        if (!isRightAngle(topLeft, topRight, bottomLeft)) return null

        val src = Mat()
        val warped = Mat()
        val srcPoints = MatOfPoint2f(
            Point(topLeft.x.toDouble(), topLeft.y.toDouble()),
            Point(topRight.x.toDouble(), topRight.y.toDouble()),
            Point(bottomRight.x.toDouble(), bottomRight.y.toDouble()),
            Point(bottomLeft.x.toDouble(), bottomLeft.y.toDouble()),
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
