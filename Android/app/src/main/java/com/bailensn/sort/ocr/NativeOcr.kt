package com.bailensn.sort.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.bailensn.sort.CellReading
import com.bailensn.sort.OcrBridge
import com.bailensn.sort.TableProgress
import java.io.File

/**
 * 面向业务流程的 OCR 门面：参数和返回类型都按 TablePipeline 的需要来，
 * 底下是 [OcrBridge] → libocr.so。
 */
class NativeOcr private constructor(
    private val bridge: OcrBridge,
    val coldLoadTimeMs: Long,
    private val refereeRec: File?,
    private val refereeCharset: File?,
) {

    /** 整表分析：全部表格逻辑都在 Native 里跑，这里只负责把失败变成异常。 */
    fun analyzeTable(
        bitmap: Bitmap,
        progress: TableProgress? = null,
    ): com.bailensn.sort.TableAnalysis =
        bridge.analyzeTable(bitmap, progress)
            ?: throw IllegalStateException("表格分析失败，错误码 ${bridge.lastError}")

    fun recognize(bitmap: Bitmap): OcrRun =
        OcrRun(bridge.recognize(bitmap).map { OcrLine(OcrBox(it.points), it.text, it.confidence) })

    fun recognizeCells(
        bitmap: Bitmap,
        rects: List<Rect>,
        scale: Float = 1f,
        onProgress: ((Float) -> Unit)? = null,
    ): List<CellReading> {
        if (rects.isEmpty()) return emptyList()
        val cells = IntArray(rects.size * 4)
        rects.forEachIndexed { index, rect ->
            cells[index * 4] = rect.left
            cells[index * 4 + 1] = rect.top
            cells[index * 4 + 2] = rect.right
            cells[index * 4 + 3] = rect.bottom
        }
        val readings = bridge.recognizeCells(bitmap, cells, scale)
        // C++ 侧没有逐块进度回调，跑完一次性推到底。
        onProgress?.invoke(1f)
        return readings
    }

    /** 没有复核模型时返回空表，调用方按两路投票走。 */
    fun recognizeCellsReferee(
        bitmap: Bitmap,
        rects: List<Rect>,
        scale: Float = 1f,
        onProgress: ((Float) -> Unit)? = null,
    ): List<CellReading> {
        if (rects.isEmpty() || !refereeReady) return emptyList()
        val cells = IntArray(rects.size * 4)
        rects.forEachIndexed { index, rect ->
            cells[index * 4] = rect.left
            cells[index * 4 + 1] = rect.top
            cells[index * 4 + 2] = rect.right
            cells[index * 4 + 3] = rect.bottom
        }
        val readings = bridge.recognizeCellsReferee(bitmap, cells, scale)
        onProgress?.invoke(1f)
        return readings
    }

    /** 复核模型 90 MB 上下，第一次要用时才加载，别拖慢冷启动。 */
    private val refereeReady: Boolean by lazy {
        val rec = refereeRec ?: return@lazy false
        val charset = refereeCharset ?: return@lazy false
        bridge.setReferee(rec.absolutePath, charset.absolutePath)
    }

    fun release() {
        bridge.close()
    }

    companion object {
        fun create(modelDir: File): NativeOcr {
            val started = SystemClock.elapsedRealtime()
            val bridge = OcrBridge(
                detModelPath = File(modelDir, "det/inference.onnx").absolutePath,
                recModelPath = File(modelDir, "rec/inference.onnx").absolutePath,
                charsetPath = File(modelDir, "rec/inference.yml").absolutePath,
            )
            // 句柄为 0 说明模型或推理后端没起来，宁可报错也别让界面拿到空表格。
            if (!bridge.isReady) {
                bridge.close()
                throw IllegalStateException("OCR 后端初始化失败，错误码 ${bridge.lastError}")
            }
            val refereeRec = File(modelDir, "referee/inference.onnx").takeIf { it.isFile }
            val refereeCharset = File(modelDir, "referee/inference.yml").takeIf { it.isFile }
            return NativeOcr(
                bridge = bridge,
                coldLoadTimeMs = SystemClock.elapsedRealtime() - started,
                refereeRec = refereeRec,
                refereeCharset = refereeCharset,
            )
        }
    }
}
