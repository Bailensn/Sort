// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.model

import android.content.Context
import java.io.File

/**
 * 模型读取来源：既可以打进 APK 的 assets，也可以是运行时下载到内部存储的文件。
 */
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
