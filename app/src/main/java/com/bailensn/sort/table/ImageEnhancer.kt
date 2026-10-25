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
