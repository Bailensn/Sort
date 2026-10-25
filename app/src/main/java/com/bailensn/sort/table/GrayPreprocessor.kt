package com.bailensn.sort.table

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 送进二值化之前先把光照压平。
 *
 * 手机拍纸面时经常一半亮一半暗（灯在侧面、手挡住一角、屏幕反光），
 * 全局固定的二值化阈值在暗的那半会糊成一片，在亮的那半又会把浅灰的表格线丢掉。
 * 这里用「原图 ÷ 模糊后的背景」做一次照度归一化：纸白的地方拉回 255，
 * 阴影只剩下字迹，之后再用自适应阈值就稳得多。
 *
 * 对截图（本来就均匀）基本无副作用，相当于多做一次平滑除法。
 */
object GrayPreprocessor {

    /**
     * @param gray 单通道灰度图（会被修改）
     * @param work 复用缓冲区，避免每次分配
     */
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
