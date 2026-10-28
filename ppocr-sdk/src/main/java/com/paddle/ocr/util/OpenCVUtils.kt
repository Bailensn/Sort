//
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// limitations under the License.

package com.paddle.ocr.util

import android.content.Context
import android.util.Log

object OpenCVUtils {

    private var initialized = false

    fun init(context: Context): Boolean {
        if (initialized) return true
        try {
            System.loadLibrary("opencv_java5")
            initialized = true
        } catch (e: UnsatisfiedLinkError) {
            Log.e("OpenCVUtils", "Failed to initialize OpenCV: ${e.message}")
        }
        return initialized
    }
}
