package com.bailensn.sort.table

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * 把图处理成「给人看也更好认」的灰度增强图。
 *
 * 两步：照度归一化（压掉侧光和阴影）+ CLAHE 拉局部对比度。
 * 用途有两个：
 *
 * 1. 二遍识别拿它当输入——褪色、发灰、半边暗的照片在原图上认不出来，增强后能认出来；
 * 2. 界面上直接给用户看，出了问题一眼能判断是「图没拍好」还是「切格切错了」。
 */
object ImageEnhancer {

    /** CLAHE 的对比度上限，比检测用的更强：这里要的是看清字，不是保住线条。 */
    private const val CLIP_LIMIT = 3.0

    /**
     * 二值化版本：给「极低置信度」的格子做第 N 组投票用。
     *
     * 和灰度增强图是不同的输入形态：灰度图保住了抗锯齿的灰度过渡，
     * 二值图把细笔画和背景彻底分开。模型在这两种输入上犯的错往往不一样，
     * 所以它们的一致性比同一张图多认几遍更有说服力。
     */
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
