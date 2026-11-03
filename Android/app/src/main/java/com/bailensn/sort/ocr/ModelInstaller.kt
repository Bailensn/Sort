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
import java.security.MessageDigest

/**
 * 模型不再随包发：首启从分享盘拉，边下边校验。
 * 每个文件的字节数和 sha256 都钉死，下坏了宁可报错也不让识别拿半截模型跑。
 */
object ModelInstaller {

    private const val VERSION = "net-1"
    private const val DIR = "ocr-models"

    /** 主源：分享目录，先 PUT 换签名直链，再 GET 下载（直链约 1 小时过期）。 */
    private const val SHARE_KEY = "Qdws1"
    private const val SHARE_API = "https://pan.fuwushang.net/api/v3/share/download"

    /** 备用源：GitHub Release，国内慢但链接稳定，主源挂了还能装得上。 */
    private const val BACKUP_BASE = "https://github.com/Bailensn/Sort/releases/download/models-v1"

    private data class Spec(
        /** 主源文件名（网盘上多加了 .pak 后缀）。 */
        val remote: String,
        /** 备用源文件名（GitHub Release 上用原名）。 */
        val backup: String,
        val local: String,
        val size: Long,
        val sha256: String,
        val required: Boolean,
    )

    // 哈希来自 tools/models.lock，和仓库里那份逐字节一致。
    private val FILES = listOf(
        Spec(
            "det.onnx.pak", "det.onnx", "det/inference.onnx", 62032837L,
            "eb13b44b25bb36f89528b68720af8a61d9cf381176107f465db1757b65d086e1", true,
        ),
        Spec(
            "rec.onnx.pak", "rec.onnx", "rec/inference.onnx", 76554979L,
            "9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba", true,
        ),
        Spec(
            "rec.yml.pak", "rec.yml", "rec/inference.yml", 150580L,
            "991b700facf5b50a7de193468207d5f4255b538dde0d312ae3b7c7a9b6873129", true,
        ),
        Spec(
            "referee.onnx.pak", "referee.onnx", "referee/inference.onnx", 84503027L,
            "d9dc333c9c7b042c6dffb8e33d72b6f65c9c1d463d0a3c2f78174fea55e94752", false,
        ),
        Spec(
            "referee.yml.pak", "referee.yml", "referee/inference.yml", 148345L,
            "2c719dba044c4e2228aef8ff92f5f575394d75d24c16de096a33b7cfd902f66d", false,
        ),
    )

    fun modelDir(context: Context): File = File(context.filesDir, DIR)

    /** 只比大小，不重算哈希：140 MB 每次启动都算一遍太慢。 */
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
            if (isReady(context)) return@withContext dir

            // 只有换了模型版本才清空；普通重试要留着 .part，才能接着下
            val version = File(dir, "version").takeIf { it.isFile }?.readText()?.trim()
            if (version != null && version != VERSION) dir.deleteRecursively()
            dir.mkdirs()

            FILES.forEachIndexed { index, spec ->
                onProgress(index, FILES.size, 0f)
                val target = File(dir, spec.local)
                target.parentFile?.mkdirs()
                val ok = runCatching {
                    download(spec, target) { fraction -> onProgress(index, FILES.size, fraction) }
                }.getOrDefault(false)
                // 复核模型缺了只是少一遍投票，主模型缺一个就没法识别
                if (!ok && spec.required) {
                    throw IOException(context.getString(R.string.model_extract_failed, spec.remote))
                }
            }
            File(dir, "version").writeText(VERSION)
            dir
        }

    private fun download(spec: Spec, target: File, onProgress: (Float) -> Unit): Boolean {
        val temp = File(target.parentFile, "${target.name}.part")
        if (!fetchVerified(spec, temp, onProgress)) {
            temp.delete()
            return false
        }
        target.delete()
        return temp.renameTo(target)
    }

    /**
     * 两次机会，每次都先试主源、再试备用源：
     * 签名过期和断流都靠重来一遍解决，主源彻底不通时直接吃备用源。
     */
    private fun fetchVerified(spec: Spec, temp: File, onProgress: (Float) -> Unit): Boolean {
        repeat(2) {
            val done = temp.takeIf { it.isFile }?.length() ?: 0L
            if (done == spec.size && sha256(temp) == spec.sha256) return true
            if (done > spec.size) temp.delete()

            val sources = listOfNotNull(signUrl(spec.remote), "$BACKUP_BASE/${spec.backup}")
            for (source in sources) {
                // 传输失败保留 .part：换个源接着下，一样是 Range 续传
                if (!get(source, temp, spec.size, onProgress)) continue
                if (temp.length() == spec.size && sha256(temp) == spec.sha256) return true
                // 下满了却对不上哈希，说明这份内容不对，只能重来
                temp.delete()
            }
        }
        return false
    }

    /** 换一个签名直链；失败返回 null，交给下一轮重试。 */
    private fun signUrl(remote: String): String? {
        val encoded = URLEncoder.encode("/$remote", "UTF-8")
        val connection = URL("$SHARE_API/$SHARE_KEY?path=$encoded").openConnection() as HttpURLConnection
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
        } catch (t: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** 断点续传：已经下了半截就带 Range，服务器不认（回 200）就从头来。 */
    private fun get(url: String, temp: File, total: Long, onProgress: (Float) -> Unit): Boolean {
        val have = temp.takeIf { it.isFile }?.length() ?: 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
            connection.connect()
            val code = connection.responseCode
            if (code != 200 && code != 206) return false
            val append = code == 206 && have > 0
            var written = if (append) have else 0L
            var reported = written
            connection.inputStream.use { input ->
                FileOutputStream(temp, append).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        // 每 2 MB 报一次就够，报太密只是白刷界面
                        if (total > 0 && written - reported >= 2L * 1024 * 1024) {
                            reported = written
                            onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
            true
        } catch (t: Throwable) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }.getOrNull()
}
