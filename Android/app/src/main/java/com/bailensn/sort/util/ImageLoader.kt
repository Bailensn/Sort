package com.bailensn.sort.util

import androidx.core.graphics.createBitmap
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.bailensn.sort.R
import java.io.ByteArrayInputStream

/** 从相册 Uri 解码出可用于 OCR 的 Bitmap：原图分辨率优先、按 EXIF 摆正。 */
object ImageLoader {

    /** 24 MP 以上才缩：小字优先，但内存别先炸。 */
    private const val MAX_PIXELS = 24_000_000

    fun decode(context: Context, uri: Uri): Bitmap {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error(context.getString(R.string.error_image_read))

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            context.getString(R.string.error_image_invalid)
        }

        // 缩采样一次，小字和小数点就永久回不来了：只有超预算或 OOM 才降
        var sampleSize = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / (sampleSize * sampleSize) > MAX_PIXELS) {
            sampleSize *= 2
        }
        var decoded = decodeOrNull(bytes, sampleSize)
        while (decoded == null && sampleSize < 8) {
            sampleSize *= 2
            decoded = decodeOrNull(bytes, sampleSize)
        }
        val bitmap = decoded ?: error(context.getString(R.string.error_image_decode))

        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        return flattenOnWhite(applyOrientation(bitmap, orientation))
    }

    private fun decodeOrNull(bytes: ByteArray, sampleSize: Int): Bitmap? = try {
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    } catch (oom: OutOfMemoryError) {
        null
    }

    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flattened = createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flattened)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        if (flattened !== bitmap) bitmap.recycle()
        return flattened
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }
}
