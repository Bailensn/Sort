package com.bailensn.sort.table

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

object ImageEnhancer {

    /** CLAHE 的对比度上限，比检测用的更强：这里要的是看清字，不是保住线条。 */
    private const val CLIP_LIMIT = 3.0

    fun binarize(bitmap: Bitmap): Bitmap? = runCatching {
        val rgba = Mat()
        val gray = Mat()
        val lighting = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            GrayPreprocessor.flatten(gray, lighting)
            Imgproc.threshold(
                gray,
                gray,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU,
            )
            val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(gray, out)
            out
        } finally {
            rgba.release()
            gray.release()
            lighting.release()
        }
    }.getOrNull()

    fun enhance(bitmap: Bitmap): Bitmap {
        val rgba = Mat()
        val gray = Mat()
        val lighting = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            GrayPreprocessor.flatten(gray, lighting)
            val clahe = Imgproc.createCLAHE(CLIP_LIMIT, org.opencv.core.Size(8.0, 8.0))
            try {
                clahe.apply(gray, gray)
            } finally {
                clahe.collectGarbage()
            }
            val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(gray, out)
            return out
        } finally {
            rgba.release()
            gray.release()
            lighting.release()
        }
    }
}
