//
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// limitations under the License.

package com.paddle.ocr.engine

import com.paddle.ocr.postprocess.CTCDecoder
import com.paddle.ocr.preprocess.RecPreprocessor
import org.opencv.core.Mat

class RecognitionEngine(
    private val ortManager: ORTSessionManager,
    private val characterList: List<String>,
    /** true = 走复核模型（更准但更慢）。 */
    private val referee: Boolean = false,
) {
    data class RecognitionResult(
        val texts: List<Pair<String, Float>>,
        val preprocessMs: Long,
        val inferenceMs: Long,
        val postprocessMs: Long,
        val timeMs: Long,
        val inputShape: List<Int>,
    )

    fun recognize(crops: List<Mat>): RecognitionResult {
        // Preprocess
        val preStart = System.currentTimeMillis()
        val preResult = RecPreprocessor.preprocessBatch(crops)
        val preprocessMs = System.currentTimeMillis() - preStart

        // Inference
        val infStart = System.currentTimeMillis()
        val (outputData, outputShape) = if (referee) {
            ortManager.runReferee(preResult.tensorData, preResult.shape)
        } else {
            ortManager.runRecognition(preResult.tensorData, preResult.shape)
        }
        val inferenceMs = System.currentTimeMillis() - infStart

        // Postprocess (CTC decode)
        val postStart = System.currentTimeMillis()
        val decoded = CTCDecoder.decode(outputData, outputShape, characterList)
        val postprocessMs = System.currentTimeMillis() - postStart

        val inputShape = preResult.shape.map { it.toInt() }
        val timeMs = preprocessMs + inferenceMs + postprocessMs
        return RecognitionResult(
            texts = decoded,
            preprocessMs = preprocessMs,
            inferenceMs = inferenceMs,
            postprocessMs = postprocessMs,
            timeMs = timeMs,
            inputShape = inputShape,
        )
    }
}
