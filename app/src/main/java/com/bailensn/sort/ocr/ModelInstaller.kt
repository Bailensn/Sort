package com.bailensn.sort.ocr

import android.content.Context
import com.bailensn.sort.R
import com.bailensn.sort.util.ScanLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * 模型不再随包发：首启从 ModelScope 的 CDN 拉，边下边校验字节数。
 * 备用源是 GitHub prerelease，CDN 不通时兜底。
 */
object ModelInstaller {

    private const val VERSION = "onnx-share-1"
    private const val DIR = "ocr-models"

    private const val MODELSCOPE = "https://modelscope.cn/models/PaddlePaddle"
    private const val BACKUP_BASE = "https://github.com/Bailensn/Sort/releases/download/models-onnx"

    /** 次选源：分享盘，要先 PUT 换签名直链。 */
    private const val SHARE_KEY = "Qdws1"
    private const val SHARE_API = "https://pan.fuwushang.net/api/v3/share/download"

    private data class Spec(
        /** 落盘后的相对路径，ppocr-sdk 按这个布局找模型。 */
        val local: String,
        /** 主源地址（ModelScope CDN）。 */
        val primary: String,
        /** 次选：分享盘上的文件名。 */
        val share: String,
        /** 兜底：GitHub release 上的文件名。 */
        val backup: String,
        val size: Long,
        val required: Boolean,
    )

    // 大小是官方仓库里的真实字节数，下完只按字节数校验。
    private val FILES = listOf(
        Spec(
            "det/inference.onnx",
            "$MODELSCOPE/PP-OCRv6_medium_det_onnx/resolve/main/inference.onnx",
            "det.onnx.pak",
            "det.onnx",
            62032837L,
            true,
        ),
        Spec(
            "rec/inference.onnx",
            "$MODELSCOPE/PP-OCRv6_medium_rec_onnx/resolve/main/inference.onnx",
            "rec.onnx.pak",
            "rec.onnx",
            76554979L,
            true,
        ),
        Spec(
            "rec/inference.yml",
            "$MODELSCOPE/PP-OCRv6_medium_rec_onnx/resolve/main/inference.yml",
            "rec.yml.pak",
            "rec.yml",
            150580L,
            true,
        ),
        // 复核模型可选：缺了只是少一遍投票
        Spec(
            "referee/inference.onnx",
            "$MODELSCOPE/PP-OCRv5_server_rec_onnx/resolve/main/inference.onnx",
            "referee.onnx.pak",
            "referee.onnx",
            84503027L,
            false,
        ),
        Spec(
            "referee/inference.yml",
            "$MODELSCOPE/PP-OCRv5_server_rec_onnx/resolve/main/inference.yml",
            "referee.yml.pak",
            "referee.yml",
            148345L,
            false,
        ),
    )

    fun modelDir(context: Context): File = File(context.filesDir, DIR)

    /** 只比大小，不重算哈希：两百多兆每次启动都算一遍太慢。 */
    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        if (File(dir, "version").takeIf { it.isFile }?.readText()?.trim() != VERSION) return false
        return FILES.filter { it.required }.all { spec ->
            File(dir, spec.local).takeIf { it.isFile }?.length() == spec.size
        }
    }

    suspend fun ensure(
        context: Context,
        onProgress: (index: Int, total: Int, fraction: Float) -> Unit = { _, _, _ -> },
    ): File =
        withContext(Dispatchers.IO) {
            val dir = modelDir(context)
            if (isReady(context)) {
                ScanLog.d("模型已就绪：${dir.absolutePath}")
                return@withContext dir
            }

            ScanLog.d("开始准备模型，目录 ${dir.absolutePath}")
            // 只有换了版本才清空；普通失败要留着 .part，下次能接着下
            val version = File(dir, "version").takeIf { it.isFile }?.readText()?.trim()
            if (version != null && version != VERSION) {
                ScanLog.w("模型版本从 $version 变成 $VERSION，整个重下")
                dir.deleteRecursively()
            }
            dir.mkdirs()

            FILES.forEachIndexed { index, spec ->
                onProgress(index, FILES.size, 0f)
                val target = File(dir, spec.local)
                target.parentFile?.mkdirs()
                ScanLog.d(
                    "模型 ${index + 1}/${FILES.size}：${spec.local} " +
                        "(${spec.size / 1024 / 1024} MB)",
                )
                val ok = runCatching {
                    fetch(spec, target) { fraction -> onProgress(index, FILES.size, fraction) }
                }.getOrElse { error ->
                    ScanLog.e("模型下载异常：${spec.local}", error)
                    false
                }
                if (ok) {
                    ScanLog.d("模型就绪：${spec.local}")
                } else {
                    ScanLog.w("模型没下成：${spec.local}")
                    if (spec.required) {
                        throw IOException(context.getString(R.string.model_download_failed, spec.local))
                    }
                    ScanLog.w("复核模型缺失，继续跑（少一遍投票）")
                }
            }
            File(dir, "version").writeText(VERSION)
            ScanLog.d("模型全部就绪")
            dir
        }

    /** 两轮：每轮依次试 CDN、分享盘、prerelease，谁的字节数对上就用谁。 */
    private fun fetch(spec: Spec, target: File, onFraction: (Float) -> Unit): Boolean {
        val temp = File(target.parentFile, "${target.name}.part")
        repeat(2) { round ->
            val sources = mutableListOf(spec.primary)
            signUrl(spec.share)?.let { sources.add(it) }
            sources.add("$BACKUP_BASE/${spec.backup}")

            for ((position, source) in sources.withIndex()) {
                val done = temp.takeIf { it.isFile }?.length() ?: 0L
                if (done == spec.size) break
                if (done > spec.size) temp.delete()
                val host = when (position) {
                    0 -> "cdn"
                    1 -> if (source.startsWith("http") && source.contains("github.com")) "backup" else "share"
                    else -> "backup"
                }
                ScanLog.d(
                    "下载 ${spec.local}（第 ${round + 1} 轮，$host，已有 $done 字节）",
                )
                download(source, temp, spec.size, onFraction)
            }

            if (temp.takeIf { it.isFile }?.length() == spec.size) {
                target.delete()
                return temp.renameTo(target)
            }
            temp.delete()
        }
        return false
    }

    /** 分享盘换签名直链；失败返回 null，这家直接跳过。 */
    private fun signUrl(remote: String): String? {
        val path = URLEncoder.encode("/$remote", "UTF-8")
        val connection =
            URL("$SHARE_API/$SHARE_KEY?path=$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(0)
            connection.outputStream.close()
            if (connection.responseCode != 200) {
                ScanLog.w("分享盘换签名失败：HTTP ${connection.responseCode}")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optInt("code") != 0) {
                ScanLog.w("分享盘换签名被拒：code ${json.optInt("code")}")
                return null
            }
            json.optString("data").takeIf { it.startsWith("http") }
        } catch (error: IOException) {
            ScanLog.w("分享盘换签名异常：${error.javaClass.simpleName}")
            null
        }
    }

    /** 断点续传：有 .part 就带 Range，服务端不认 206 就整份重下。 */
    private fun download(
        url: String,
        temp: File,
        expected: Long,
        onFraction: (Float) -> Unit,
    ): Boolean {
        return try {
            val start = temp.takeIf { it.isFile }?.length() ?: 0L
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 120000
            if (start > 0L) connection.setRequestProperty("Range", "bytes=$start-")
            val code = connection.responseCode
            if (code != 200 && code != 206) {
                ScanLog.w("下载失败：HTTP $code（$url）")
                return false
            }

            val append = start > 0L && code == 206
            var copied = if (append) start else 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(temp, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (expected > 0) {
                            // 一兆一个回调太吵，整百分比变一次才通知界面
                            val percent = (copied * 100 / expected).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onFraction((percent / 100f).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            true
        } catch (error: IOException) {
            ScanLog.w("下载中断：${error.javaClass.simpleName}")
            false
        }
    }
}
