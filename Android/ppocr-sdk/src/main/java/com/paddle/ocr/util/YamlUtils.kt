//
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// limitations under the License.

package com.paddle.ocr.util

object YamlUtils {

    fun leadingSpaces(line: String): Int {
        return line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
    }
}
