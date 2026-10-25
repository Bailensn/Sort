package com.bailensn.sort.ocr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException

/**
 * 模型随 APK 以 .xz 形式内置，首次启动解压到内部存储，之后直接用文件加载。
 * LZMA2 是通用压缩里对二进制最狠的一档（比 zip 小约 10%~15%）。
 */
object ModelInstaller {

    private const val VERSION = "medium-xz-1"
    private const val DIR = "ocr-models"
    private val FILES = listOf(
        "det/inference.onnx",
        "rec/inference.onnx",
        "rec/inference.yml",
    )

    fun modelDir(context: Context): File = File(context.filesDir, DIR)

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        if (File(dir, "version").takeIf { it.isFile }?.readText()?.trim() != VERSION) return false
        return FILES.all { File(dir, it).takeIf { file -> file.isFile }?.length() ?: 0L > 1024L }
    }

    suspend fun ensure(context: Context, onStage: (String) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val dir = modelDir(context)
            if (isReady(context)) return@withContext dir

            // 版本变了就整个重来，避免新旧模型混用。
            dir.deleteRecursively()
            dir.mkdirs()

            FILES.forEachIndexed { index, relative ->
                onStage("正在解压内置模型 ${index + 1}/${FILES.size}")
                val out = File(dir, relative)
                out.parentFile?.mkdirs()
                val temp = File(out.parentFile, "${out.name}.part")
                context.assets.open("models/$relative.xz").use { asset ->
                    XZInputStream(asset).use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (temp.length() < 1024L || !temp.renameTo(out)) {
                    throw IOException("内置模型解压失败：$relative")
                }
            }
            File(dir, "version").writeText(VERSION)
            dir
        }
}
