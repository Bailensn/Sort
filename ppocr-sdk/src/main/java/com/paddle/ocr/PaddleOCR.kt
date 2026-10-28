//
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// limitations under the License.

package com.paddle.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.paddle.ocr.engine.OCREngine
import com.paddle.ocr.engine.OCREngineResult
import com.paddle.ocr.model.CellReading
import com.paddle.ocr.model.OCRRunResult
import com.paddle.ocr.model.OCRError
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PaddleOCR private constructor(
    private val engine: OCREngine,
) {
    /** Time spent loading ONNX models (milliseconds). */
    val coldLoadTimeMs: Long get() = engine.coldLoadTimeMs

    companion object {

        suspend fun create(context: Context): PaddleOCR {
            val appContext = context.applicationContext
            return withContext(Dispatchers.IO) {
                val engine = OCREngine(appContext, PaddleOCRConfig(), EngineConfig())
                PaddleOCR(engine)
            }
        }

        suspend fun create(
            context: Context,
            config: PaddleOCRConfig,
            engineConfig: EngineConfig = EngineConfig(),
        ): PaddleOCR {
            val appContext = context.applicationContext
            return withContext(Dispatchers.IO) {
                val engine = OCREngine(appContext, config, engineConfig)
                PaddleOCR(engine)
            }
        }

        suspend fun createWithModels(
            context: Context,
            modelDir: File,
            config: PaddleOCRConfig = PaddleOCRConfig(),
            engineConfig: EngineConfig = EngineConfig(),
        ): PaddleOCR {
            val appContext = context.applicationContext
            return withContext(Dispatchers.IO) {
                val engine = OCREngine(appContext, config, engineConfig, modelDir = modelDir)
                PaddleOCR(engine)
            }
        }

        suspend fun create(
            context: Context,
            config: PaddleOCRConfig,
            engineConfig: EngineConfig,
            detModelAssetPath: String,
            recModelAssetPath: String,
            recConfigAssetPath: String,
        ): PaddleOCR {
            val appContext = context.applicationContext
            return withContext(Dispatchers.IO) {
                val engine = OCREngine(
                    appContext, config, engineConfig,
                    detModelAsset = detModelAssetPath,
                    recModelAsset = recModelAssetPath,
                    recConfigAsset = recConfigAssetPath,
                )
                PaddleOCR(engine)
            }
        }
    }

    suspend fun recognize(bitmap: Bitmap): OCRRunResult {
        if (bitmap.width == 0 || bitmap.height == 0) {
            throw OCRError.InvalidImage()
        }
        return recognizeResult { engine.run(bitmap) }
    }

    suspend fun recognize(imageBytes: ByteArray): OCRRunResult {
        if (imageBytes.isEmpty()) {
            throw OCRError.InvalidImage()
        }
        return recognizeResult { engine.run(imageBytes) }
    }

    private suspend fun recognizeResult(runEngine: () -> OCREngineResult): OCRRunResult {
        return withContext(Dispatchers.IO) {
            val result = runEngine()
            OCRRunResult(
                results = result.results,
                detectionTimeMs = result.detectionTimeMs,
                recognitionTimeMs = result.recognitionTimeMs,
                totalTimeMs = result.totalTimeMs,
                lineCount = result.lineCount,
                detPreprocessMs = result.detPreprocessMs,
                detInferenceMs = result.detInferenceMs,
                detPostprocessMs = result.detPostprocessMs,
                recPreprocessMs = result.recPreprocessMs,
                recInferenceMs = result.recInferenceMs,
                recPostprocessMs = result.recPostprocessMs,
                pipelineOverheadMs = result.pipelineOverheadMs,
                coldLoadTimeMs = result.coldLoadTimeMs,
                detInputShape = result.detInputShape,
                recInputShapes = result.recInputShapes,
                perLineRecMs = result.perLineRecMs,
            )
        }
    }

    suspend fun recognizeCells(bitmap: Bitmap, cells: List<Rect>, scale: Float = 1f): List<CellReading> {
        if (bitmap.width == 0 || bitmap.height == 0) {
            throw OCRError.InvalidImage()
        }
        if (cells.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            engine.recognizeCells(bitmap, cells, scale)
        }
    }

    /** 用复核模型（更大更准）再认一遍；没有复核模型时返回空列表。 */
    suspend fun recognizeCellsReferee(bitmap: Bitmap, cells: List<Rect>, scale: Float = 1f): List<CellReading> {
        if (cells.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            engine.recognizeCellsReferee(bitmap, cells, scale)
        }
    }

    suspend fun release() {
        withContext(Dispatchers.IO) {
            engine.release()
        }
    }
}
