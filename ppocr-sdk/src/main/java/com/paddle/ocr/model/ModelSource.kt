//
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// limitations under the License.

package com.paddle.ocr.model

import android.content.Context
import java.io.File

sealed interface ModelSource {

    fun read(): ByteArray

    /** APK 内置模型。 */
    class Asset(private val context: Context, private val path: String) : ModelSource {
        override fun read(): ByteArray = context.assets.open(path).use { it.readBytes() }
    }

    /** 运行时下载到磁盘的模型。 */
    class Local(private val file: File) : ModelSource {
        override fun read(): ByteArray = file.readBytes()
    }
}
