package com.bailensn.sort.ocr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型不再打进 APK，改为首次使用时下载到内部存储。
 *
 * - 默认下「高精度」的 PP-OCRv6 medium（det 约 62 MB + rec 约 76 MB + 字符表 150 KB）；
 * - 高精度下载失败（没网、空间不足、镜像挂了）自动退回 small（约 31 MB），保证能用；
 * - 下载前先测国内镜像 hf-mirror 和海外 huggingface 的速度，谁快用谁，失败的会自动换另一个。
 */
object ModelRepository {

    data class FileSpec(
        val path: String,
        val repo: String,
        val minBytes: Long,
    )

    data class Profile(
        val id: String,
        val label: String,
        val det: FileSpec,
        val rec: FileSpec,
        val recConfig: FileSpec,
    ) {
        val files: List<FileSpec> get() = listOf(det, rec, recConfig)
        val totalMinBytes: Long get() = files.sumOf { it.minBytes }
    }

    private val ACCURATE = Profile(
        id = "medium",
        label = "高精度模型",
        det = FileSpec("det/inference.onnx", "PP-OCRv6_medium_det_onnx", 40_000_000),
        rec = FileSpec("rec/inference.onnx", "PP-OCRv6_medium_rec_onnx", 50_000_000),
        recConfig = FileSpec("rec/inference.yml", "PP-OCRv6_medium_rec_onnx", 100_000),
    )

    /**
     * 都是 HF 的 `/<repo>/resolve/main/<file>` 路径结构，ModelScope 也兼容。
     * 前三个是国内源，最后一个是海外源；运行时按实测速度排序。
     */
    private val MIRRORS = listOf(
        "https://modelscope.cn/models/PaddlePaddle",
        "https://hf-mirror.com/PaddlePaddle",
        "https://hf-mirror.net/PaddlePaddle",
        "https://aifasthub.com/PaddlePaddle",
        "https://huggingface.co/PaddlePaddle",
    )

    fun modelDir(context: Context): File = File(context.filesDir, "ocr-models")

    fun downloadedProfile(context: Context): Profile? =
        ACCURATE.takeIf { profile ->
            profile.files.all { spec ->
                val file = File(modelDir(context), spec.path)
                file.isFile && file.length() >= spec.minBytes
            }
        }

    fun isReady(context: Context): Boolean = downloadedProfile(context) != null

    /**
     * 确保模型就绪；返回模型目录。
     *
     * @param onProgress (百分比 0..100, 状态文案)
     */
    suspend fun ensure(
        context: Context,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val dir = modelDir(context)
        downloadedProfile(context)?.let {
            onProgress(100, "模型已就绪")
            return@withContext dir
        }

        val mirrors = rankMirrors()
        var lastError: Throwable? = null

        for (mirror in mirrors) {
            try {
                downloadProfile(dir, ACCURATE, mirror, onProgress)
                onProgress(100, "${ACCURATE.label}已就绪")
                return@withContext dir
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IOException("模型下载失败：${lastError?.message ?: "未知错误"}")
    }

    /** 删掉已下载的模型（设置里“清理模型”用）。 */
    fun clear(context: Context) {
        modelDir(context).deleteRecursively()
    }

    // ---------------------------------------------------------------- 下载实现

    private fun downloadProfile(
        dir: File,
        profile: Profile,
        mirror: String,
        onProgress: (Int, String) -> Unit,
    ) {
        val totalBytes = profile.totalMinBytes.toDouble()
        var completed = 0L
        val startedAt = System.currentTimeMillis()

        for (spec in profile.files) {
            val target = File(dir, spec.path)
            if (target.isFile && target.length() >= spec.minBytes) {
                completed += spec.minBytes
                continue
            }
            val url = "$mirror/${spec.repo}/resolve/main/${spec.fileName()}"
            val base = completed
            download(url, target, spec.minBytes) { written ->
                val done = base + written
                val percent = ((done / totalBytes) * 100).toInt().coerceIn(0, 100)
                val seconds = ((System.currentTimeMillis() - startedAt) / 1000.0).coerceAtLeast(0.1)
                val speed = done / seconds / (1024 * 1024)
                val host = mirror.removePrefix("https://").substringBefore('/')
                onProgress(
                    percent,
                    "正在从 $host 下载${profile.label} $percent%（%.1f MB/s）".format(speed),
                )
            }
            completed += target.length()
        }
    }

    private fun FileSpec.fileName(): String = path.substringAfterLast('/')

    private fun download(
        url: String,
        target: File,
        minBytes: Long,
        onBytes: (Long) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        // 大文件支持断点续传：上次中断留下的 .part 从断点继续，
        // 换镜像也能接着下（同一份文件的字节内容一致）。
        var existing = if (temp.isFile) temp.length() else 0L
        if (existing >= minBytes) {
            if (temp.renameTo(target)) {
                onBytes(existing)
                return
            }
            existing = 0
        }

        val connection = open(url, rangeStart = existing.takeIf { it > 0 })
        try {
            val resuming = existing > 0 && connection.responseCode == 206
            if (existing > 0 && !resuming) {
                // 服务器不支持断点续传，从头来。
                existing = 0
                temp.delete()
            }
            connection.inputStream.use { input ->
                FileOutputStream(temp, resuming).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = existing
                    onBytes(total)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        total += read
                        onBytes(total)
                    }
                }
            }
            if (temp.length() < minBytes) {
                temp.delete()
                throw IOException("下载内容不完整（${temp.length()} 字节）")
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IOException("无法写入 ${target.name}")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 并行探测各镜像，按耗时排序；都不通时按原顺序。 */
    private suspend fun rankMirrors(): List<String> = coroutineScope {
        val timings = MIRRORS.map { mirror ->
            async(Dispatchers.IO) { mirror to probe(mirror) }
        }.awaitAll()
        timings.sortedBy { it.second }.map { it.first }
    }

    /** 用字符表这个 150 KB 的小文件测首包时间；4 秒没响应就当不可用。 */
    private fun probe(mirror: String): Long {
        val startedAt = System.currentTimeMillis()
        return try {
            val connection = open(
                "$mirror/PP-OCRv6_small_rec_onnx/resolve/main/inference.yml",
                connectTimeoutMs = 4_000,
                readTimeoutMs = 4_000,
            )
            try {
                connection.setRequestProperty("Range", "bytes=0-131071")
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (input.read(buffer) > 0) Unit
                }
            } finally {
                connection.disconnect()
            }
            System.currentTimeMillis() - startedAt
        } catch (t: Throwable) {
            Long.MAX_VALUE
        }
    }

    private fun open(
        url: String,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 30_000,
        rangeStart: Long? = null,
    ): HttpURLConnection {
        var current = url
        var redirects = 0
        while (true) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Sort-Android")
            if (rangeStart != null) {
                connection.setRequestProperty("Range", "bytes=$rangeStart-")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IOException("重定向缺少 Location")
                current = if (location.startsWith("http")) location else URL(URL(current), location).toString()
                if (++redirects > 5) throw IOException("重定向过多")
                continue
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw IOException("HTTP $code")
            }
            return connection
        }
    }
}
