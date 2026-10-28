package com.bailensn.sort.ocr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException

object ModelInstaller {

    private const val VERSION = "medium-xz-2"
    private const val DIR = "ocr-models"
    /** 没解压出来就没法识别，缺一个都算失败。 */
    private val REQUIRED = listOf(
        "det/inference.onnx",
        "rec/inference.onnx",
        "rec/inference.yml",
    )

    /** 复核模型是可选的：缺了只是少一遍投票，不影响主流程。 */
    private val OPTIONAL = listOf(
        "referee/inference.onnx",
        "referee/inference.yml",
    )

    fun modelDir(context: Context): File = File(context.filesDir, DIR)

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        if (File(dir, "version").takeIf { it.isFile }?.readText()?.trim() != VERSION) return false
        return REQUIRED.all { File(dir, it).takeIf { file -> file.isFile }?.length() ?: 0L > 1024L }
    }

    suspend fun ensure(context: Context, onStage: (String) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val dir = modelDir(context)
            if (isReady(context)) return@withContext dir

            // 版本变了就整个重来，避免新旧模型混用。
            dir.deleteRecursively()
            dir.mkdirs()

            val files = REQUIRED + OPTIONAL
            files.forEachIndexed { index, relative ->
                onStage("正在解压内置模型 ${index + 1}/${files.size}")
                val out = File(dir, relative)
                out.parentFile?.mkdirs()
                val temp = File(out.parentFile, "${out.name}.part")
                val extracted = runCatching {
                    context.assets.open("models/$relative.xz").use { asset ->
                        XZInputStream(asset).use { input ->
                            temp.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    temp.length() >= 1024L && temp.renameTo(out)
                }.getOrDefault(false)
                if (!extracted && relative in REQUIRED) {
                    throw IOException("内置模型解压失败：$relative")
                }
                if (!extracted) temp.delete()
            }
            File(dir, "version").writeText(VERSION)
            dir
        }
}
