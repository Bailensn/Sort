package com.bailensn.sort.ui.table

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 存一份到缓存，再交给系统相册；写失败就当没点。 */
internal suspend fun openInGallery(context: Context, bitmap: Bitmap, name: String) =
    withContext(Dispatchers.IO) {
        runCatching {
            // 放外部缓存：/sdcard/Android/data/<包名>/cache/preview/，用文件管理器能直接翻到。
            val base = context.externalCacheDir ?: context.cacheDir
            val dir = File(base, "preview").apply { mkdirs() }
            val file = File(dir, name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            launchGallery(context, file)
        }
        Unit
    }

/** 用系统相册打开私有目录里的图片。 */
internal fun launchGallery(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
