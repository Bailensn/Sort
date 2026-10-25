import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bailensn.sort"
    compileSdk = 37

    // AGP 9.4 默认 NDK 是 28.2.13676358；这里显式用 r30（sysroot 支持到 API 37）。
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.bailensn.sort"
        // ppocr-sdk（ONNX Runtime）要求 26。
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 只构建 64 位 ABI：Play 要求 64 位，CI 也能快一半。
            // 需要 32 位设备时把 "armeabi-v7a" / "x86" 加回来。
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // 见 libs.versions.toml 里的说明：AGP 9.4 只认 SDK CMake 3.22.1。
            version = libs.versions.cmake.get()
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        // JDK 21 编译；Kotlin 的 jvmTarget 在内置 Kotlin 下默认跟随
        // targetCompatibility，所以不需要再写 kotlin { compilerOptions { ... } }。
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ---------------------------------------------------------------------------
// PP-OCRv6 small ONNX 模型：构建期下载到 assets，避免把 30 MB 二进制塞进 git。
// 已经存在（本地或 CI 缓存）就跳过；下载失败会重试 3 次。
// ---------------------------------------------------------------------------
val ocrModelDir = layout.projectDirectory.dir("src/main/assets/models")
val ocrModels = mapOf(
    "det/inference.onnx" to
        "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx",
    "det/inference.yml" to
        "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.yml",
    "rec/inference.onnx" to
        "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx",
    "rec/inference.yml" to
        "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.yml",
)

val downloadOcrModels = tasks.register("downloadOcrModels") {
    group = "ocr"
    description = "下载 PP-OCRv6 small ONNX 模型到 app/src/main/assets/models"
    outputs.dir(ocrModelDir)

    doLast {
        val root = ocrModelDir.asFile
        ocrModels.forEach { (relativePath, url) ->
            val target = File(root, relativePath)
            if (target.isFile && target.length() > 1024L) {
                return@forEach
            }
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.part")
            var failure: Exception? = null
            for (attempt in 1..3) {
                try {
                    URI(url).toURL().openStream().use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    failure = null
                    break
                } catch (e: Exception) {
                    failure = e
                    temp.delete()
                    Thread.sleep(1_000L * attempt)
                }
            }
            if (failure != null) {
                throw GradleException("下载 OCR 模型失败：$url（${failure.message}）")
            }
            if (!temp.renameTo(target)) {
                throw GradleException("无法写入模型文件：${target.absolutePath}")
            }
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(downloadOcrModels)
}

dependencies {
    implementation(project(":ppocr-sdk"))

    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
