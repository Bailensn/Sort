package com.bailensn.sort.ocr

import android.content.Context
import com.bailensn.sort.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 模型不再随包发：首启从分享盘拉，边下边校验字节数。
 * 网盘只给签名直链（约一小时过期），所以每次下载前现换一个。
 */
object ModelInstaller {

    private const val VERSION = "onnx-share-1"
    private const val DIR = "ocr-models"

    /** 分享盘目录 key，对应 https://pan.fuwushang.net/s/Qdws1。 */
    private const val SHARE_KEY = "Qdws1"
    private const val SHARE_API = "https://pan.fuwushang.net/api/v3/share/download"

    private data class Spec(
        /** 网盘上的文件名。 */
        val remote: String,
        /** 落盘后的相对路径，ppocr-sdk 按这个布局找模型。 */
        val local: String,
        val size: Long,
        val required: Boolean,
    )

    // 大小是网盘上文件的真实字节数，下完只按字节数校验。
    private val FILES = listOf(
        Spec("det.onnx.pak", "det/inference.onnx", 62032837L, true),
        Spec("rec.onnx.pak", "rec/inference.onnx", 76554979L, true),
        Spec("rec.yml.pak", "rec/inference.yml", 150580L, true),
        // 复核模型可选：缺了只是少一遍投票
        Spec("referee.onnx.pak", "referee/inference.onnx", 84503027L, false),
        Spec("referee.yml.pak", "referee/inference.yml", 148345L, false),
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

    suspend fun ensure(context: Context, onStage: (index: Int, total: Int) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            val dir = modelDir(context)
            if (isReady(context)) return@withContext dir

            // 只有换了版本才清空；普通失败要留着 .part，下次能接着下
            val version = File(dir, "version").takeIf { it.isFile }?.readText()?.trim()
            if (version != null && version != VERSION) dir.deleteRecursively()
            dir.mkdirs()

            FILES.forEachIndexed { index, spec ->
                onStage(index, FILES.size)
                val target = File(dir, spec.local)
                target.parentFile?.mkdirs()
                val ok = runCatching { fetch(spec, target) }.getOrDefault(false)
                if (!ok && spec.required) {
                    throw IOException(context.getString(R.string.model_download_failed, spec.remote))
                }
            }
            File(dir, "version").writeText(VERSION)
            dir
        }

    /** 两轮：签名过期或断流都靠换一个链接重来。 */
    private fun fetch(spec: Spec, target: File): Boolean {
        val temp = File(target.parentFile, "${target.name}.part")
        repeat(2) {
            val done = temp.takeIf { it.isFile }?.length() ?: 0L
            if (done == spec.size) {
                target.delete()
                return temp.renameTo(target)
            }
            if (done > spec.size) temp.delete()

            val url = signUrl(spec.remote) ?: return@repeat
            if (!download(url, temp)) return@repeat
            if (temp.length() == spec.size) {
                target.delete()
                return temp.renameTo(target)
            }
            temp.delete()
        }
        return false
    }

    /** 换一个签名直链；失败返回 null，交给下一轮重试。 */
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
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optInt("code") != 0) return null
            json.optString("data").takeIf { it.startsWith("http") }
        } catch (error: IOException) {
            null
        }
    }

    /** 断点续传：有 .part 就带 Range，服务端不认 206 就整份重下。 */
    private fun download(url: String, temp: File): Boolean {
        return try {
            val start = temp.takeIf { it.isFile }?.length() ?: 0L
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 120000
            if (start > 0L) connection.setRequestProperty("Range", "bytes=$start-")
            val code = connection.responseCode
            if (code != 200 && code != 206) return false

            val append = start > 0L && code == 206
            connection.inputStream.use { input ->
                FileOutputStream(temp, append).use { output -> input.copyTo(output) }
            }
            true
        } catch (error: IOException) {
            false
        }
    }
}
