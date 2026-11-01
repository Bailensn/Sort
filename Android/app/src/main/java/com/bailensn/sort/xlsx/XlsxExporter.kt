package com.bailensn.sort.xlsx

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportedFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
)

object XlsxExporter {

    const val MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    fun export(context: Context, rows: List<List<String>>): ExportedFile {
        // 外部缓存，导出的文件在文件管理器里能直接看到。
        val base = context.externalCacheDir ?: context.cacheDir
        val directory = File(base, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(directory, "table_$stamp.xlsx")
        file.outputStream().buffered().use { output ->
            XlsxWriter.write(rows, output)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return ExportedFile(uri = uri, name = file.name, sizeBytes = file.length())
    }

    fun shareIntent(exported: ExportedFile): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, exported.uri)
        putExtra(Intent.EXTRA_SUBJECT, exported.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
