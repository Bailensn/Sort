package com.bailensn.sort.table

import android.graphics.Bitmap

object TableEdgeLines {

    /** 边缘留白超过画面这个比例、且里面有墨迹，才补线。 */
    private const val EDGE_RATIO = 0.25f

    /** 一条横带里暗像素占比到多少算「有内容」。 */
    private const val INK_RATIO = 0.02f

    fun extend(bitmap: Bitmap, detection: LineDetection): LineDetection {
        if (!detection.isUsable) return detection
        val width = detection.width
        val height = detection.height
        val horizontals = detection.horizontal.toMutableList()
        val verticals = detection.vertical.toMutableList()

        val topLine = horizontals.minByOrNull { it.valueAt(width / 2f) }?.valueAt(width / 2f)
        val bottomLine = horizontals.maxByOrNull { it.valueAt(width / 2f) }?.valueAt(width / 2f)
        val leftLine = verticals.minByOrNull { it.valueAt(height / 2f) }?.valueAt(height / 2f)
        val rightLine = verticals.maxByOrNull { it.valueAt(height / 2f) }?.valueAt(height / 2f)

        // 第一行被截断：补画面顶边。
        if (topLine != null && topLine > height * 0.01f && topLine < height * EDGE_RATIO &&
            hasInkRow(bitmap, 0, topLine.toInt().coerceAtLeast(1))
        ) {
            horizontals.add(FittedLine(0f, 0f))
        }
        // 最后一行被截断：补画面底边。
        if (bottomLine != null && bottomLine < height * 0.99f && bottomLine > height * (1f - EDGE_RATIO) &&
            hasInkRow(bitmap, bottomLine.toInt().coerceAtMost(height - 1), height)
        ) {
            horizontals.add(FittedLine(0f, height.toFloat()))
        }
        // 左右同理：最左/最右列被截断时补竖线。
        if (leftLine != null && leftLine > width * 0.01f && leftLine < width * EDGE_RATIO &&
            hasInkColumn(bitmap, 0, leftLine.toInt().coerceAtLeast(1))
        ) {
            verticals.add(FittedLine(0f, 0f))
        }
        if (rightLine != null && rightLine < width * 0.99f && rightLine > width * (1f - EDGE_RATIO) &&
            hasInkColumn(bitmap, rightLine.toInt().coerceAtMost(width - 1), width)
        ) {
            verticals.add(FittedLine(0f, width.toFloat()))
        }

        if (horizontals.size == detection.horizontal.size && verticals.size == detection.vertical.size) {
            return detection
        }
        return detection.copy(horizontal = horizontals, vertical = verticals)
    }

    private fun hasInkRow(bitmap: Bitmap, fromY: Int, toY: Int): Boolean {
        val step = (bitmap.width / 160).coerceAtLeast(1)
        var y = fromY.coerceAtLeast(0)
        while (y < toY.coerceAtMost(bitmap.height)) {
            var dark = 0
            var total = 0
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val luminance = (
                    ((pixel shr 16) and 0xFF) * 299 +
                        ((pixel shr 8) and 0xFF) * 587 +
                        (pixel and 0xFF) * 114
                    ) / 1000
                if (luminance < 160) dark++
                total++
                x += step
            }
            if (total > 0 && dark.toDouble() / total >= INK_RATIO) return true
            y += 2
        }
        return false
    }

    private fun hasInkColumn(bitmap: Bitmap, fromX: Int, toX: Int): Boolean {
        val step = (bitmap.height / 160).coerceAtLeast(1)
        var x = fromX.coerceAtLeast(0)
        while (x < toX.coerceAtMost(bitmap.width)) {
            var dark = 0
            var total = 0
            var y = 0
            while (y < bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                val luminance = (
                    ((pixel shr 16) and 0xFF) * 299 +
                        ((pixel shr 8) and 0xFF) * 587 +
                        (pixel and 0xFF) * 114
                    ) / 1000
                if (luminance < 160) dark++
                total++
                y += step
            }
            if (total > 0 && dark.toDouble() / total >= INK_RATIO) return true
            x += 2
        }
        return false
    }
}
