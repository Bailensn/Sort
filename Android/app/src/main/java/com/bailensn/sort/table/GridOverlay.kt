package com.bailensn.sort.table

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** 把拟合出的表格线画到图上看对齐情况，方便和原图直接对照。 */
object GridOverlay {

    fun draw(bitmap: Bitmap, detection: LineDetection): Bitmap {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val paint = Paint().apply {
            color = Color.argb(190, 0, 150, 136)
            strokeWidth = maxOf(1.5f, bitmap.width / 700f)
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        detection.horizontal.forEach { line ->
            canvas.drawLine(0f, line.valueAt(0f), width, line.valueAt(width), paint)
        }
        detection.vertical.forEach { line ->
            canvas.drawLine(line.valueAt(0f), 0f, line.valueAt(height), height, paint)
        }
        return copy
    }
}
