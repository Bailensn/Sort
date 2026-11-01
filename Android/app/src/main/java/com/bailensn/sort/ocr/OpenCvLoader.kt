package com.bailensn.sort.ocr

/** OpenCV 的 native 库只在第一次用到时加载，失败就当没装。 */
object OpenCvLoader {

    private var loaded = false

    fun init(): Boolean {
        if (loaded) return true
        return runCatching {
            System.loadLibrary("opencv_java5")
            loaded = true
            true
        }.getOrDefault(false)
    }
}
