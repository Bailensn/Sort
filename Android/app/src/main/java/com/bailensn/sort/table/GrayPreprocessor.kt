package com.bailensn.sort.table

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object GrayPreprocessor {

    fun flatten(gray: Mat, work: Mat) {
        if (gray.empty()) return
        // sigma 取画面短边的 1/30：比任何单个字/格都大，只留下「照明」这一层低频信息。
        val sigma = (minOf(gray.cols(), gray.rows()) / 30.0).coerceAtLeast(8.0)
        Imgproc.GaussianBlur(gray, work, Size(0.0, 0.0), sigma)
        // 背景全黑（整幅照片纯黑）时除法会除零，先兜一下。
        if (Core.mean(work).`val`[0] < 1.0) return
        Core.divide(gray, work, work, 255.0)
        work.copyTo(gray)
    }

    /** 提一档局部对比度：褪色的打印稿、灰底截图靠这一步把线和字拉出来。 */
    fun enhanceContrast(gray: Mat) {
        if (gray.empty()) return
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        try {
            clahe.apply(gray, gray)
        } finally {
            clahe.collectGarbage()
        }
    }
}
