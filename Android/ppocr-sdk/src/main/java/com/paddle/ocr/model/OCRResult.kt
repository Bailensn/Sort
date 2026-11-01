//
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// limitations under the License.

package com.paddle.ocr.model

data class OCRResult(
    val box: OCRBox,
    val text: String,
    val confidence: Float,
    val wordBoxes: List<OCRBox>? = null,
)
