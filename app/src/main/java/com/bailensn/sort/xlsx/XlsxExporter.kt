package com.bailensn.sort.xlsx

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.bailensn.sort.table.TableGrid
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

    fun export(context: Context, grid: TableGrid): ExportedFile {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(directory, "table_$stamp.xlsx")
        file.outputStream().buffered().use { output ->
            XlsxWriter.write(grid, output)
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
