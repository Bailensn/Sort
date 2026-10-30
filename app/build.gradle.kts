buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // 构建期把模型压成 .xz（LZMA2），比 zip 再小一截。
        classpath("org.tukaani:xz:1.12")
    }
}

import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bailensn.sort"
    compileSdk = 37

    // AGP 默认 NDK 是 28.2；这里显式用 r30（sysroot 支持到 API 37）。
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
            // 只出 arm64-v8a：x86_64 只有模拟器用得上，却要让每个 .so 多带一份，
            // APK 直接大十几 MB。以后真要支持模拟器，用下面的 splits 拆成独立包，
            // 不要塞进同一个 APK：
            //   splits { abi { isEnable = true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk = false } }
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            // R8 + 资源压缩：release 包体明显小一圈，JNI 符号靠 proguard-rules 保住。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // 见 libs.versions.toml 里的说明：AGP 只认 SDK CMake 3.22.1。
            version = libs.versions.cmake.get()
        }
    }

    androidResources {
        // 已经是 xz，别让 aapt 再压一遍浪费时间。
        noCompress += "xz"
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

    lint {
        // 只出 arm64-v8a，ChromeOS 的 x86_64 是刻意不支持（见 README「ABI」）。
        disable += "ChromeOsAbiSupport"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ---------------------------------------------------------------------------
// 内置「高精度」模型（PP-OCRv6 medium）：不再运行时下载，APK 自带。
// 下载到 build/generated/ocrModels/models/，由 AGP 当作生成的 assets 目录并入。
// ---------------------------------------------------------------------------
// 每个文件给国内/海外两个源：CI 在海外，本机在国内，谁通用谁。
val bundledOcrModels = mapOf(
    "det/inference.onnx" to listOf(
        "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_det_onnx/resolve/main/inference.onnx",
        "https://modelscope.cn/models/PaddlePaddle/PP-OCRv6_medium_det_onnx/resolve/main/inference.onnx",
    ),
    "rec/inference.onnx" to listOf(
        "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/main/inference.onnx",
        "https://modelscope.cn/models/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/main/inference.onnx",
    ),
    // 复核模型：PP-OCRv5 server rec，比 v6 medium 更大，只在难认的格子上投票。
    "referee/inference.onnx" to listOf(
        "https://huggingface.co/PaddlePaddle/PP-OCRv5_server_rec_onnx/resolve/main/inference.onnx",
        "https://hf-mirror.com/PaddlePaddle/PP-OCRv5_server_rec_onnx/resolve/main/inference.onnx",
    ),
    "referee/inference.yml" to listOf(
        "https://huggingface.co/PaddlePaddle/PP-OCRv5_server_rec_onnx/resolve/main/inference.yml",
        "https://hf-mirror.com/PaddlePaddle/PP-OCRv5_server_rec_onnx/resolve/main/inference.yml",
    ),
    "rec/inference.yml" to listOf(
        "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/main/inference.yml",
        "https://modelscope.cn/models/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/main/inference.yml",
    ),
)

abstract class BundleOcrModelsTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val rawDir: DirectoryProperty

    @get:Input
    abstract val urls: MapProperty<String, List<String>>

    @TaskAction
    fun bundle() {
        val assetsRoot = File(outputDir.get().asFile, "models")
        val rawRoot = rawDir.get().asFile

        urls.get().forEach { (relativePath, candidates) ->
            val raw = File(rawRoot, relativePath)
            if (!raw.isFile || raw.length() < 1024L) {
                download(raw, candidates)
            }
            val packed = File(assetsRoot, "$relativePath.xz")
            if (packed.isFile && packed.length() > 1024L) return@forEach
            packed.parentFile?.mkdirs()
            compress(raw, packed)
        }
    }

    private fun download(target: File, candidates: List<String>) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        var failure: Exception? = null
        for (url in candidates) {
            for (attempt in 1..2) {
                try {
                    URI(url).toURL().openStream().use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    failure = null
                    if (!temp.renameTo(target)) {
                        throw GradleException("无法写入模型文件：${target.absolutePath}")
                    }
                    return
                } catch (e: Exception) {
                    failure = e
                    temp.delete()
                    Thread.sleep(1_000L * attempt)
                }
            }
        }
        throw GradleException("下载内置模型失败：${failure?.message}")
    }

    /** LZMA2 最高压缩预设（9 | EXTREME）。ONNX 权重压不太动，但能省一点是一点。 */
    private fun compress(source: File, target: File) {
        val temp = File(target.parentFile, "${target.name}.part")
        source.inputStream().buffered().use { input ->
            org.tukaani.xz.XZOutputStream(
                temp.outputStream().buffered(),
                org.tukaani.xz.LZMA2Options(9),
            ).use { output -> input.copyTo(output) }
        }
        if (!temp.renameTo(target)) {
            throw GradleException("无法写入压缩模型：${target.absolutePath}")
        }
    }
}

val bundleOcrModels = tasks.register<BundleOcrModelsTask>("bundleOcrModels") {
    group = "ocr"
    description = "下载高精度 OCR 模型并压缩成 .xz，随 APK 内置"
    outputDir.set(layout.buildDirectory.dir("generated/ocrModels"))
    rawDir.set(layout.buildDirectory.dir("ocrRaw"))
    urls.set(bundledOcrModels)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            bundleOcrModels,
            BundleOcrModelsTask::outputDir,
        )
    }
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
    implementation("org.tukaani:xz:1.12")

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.backdrop)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
