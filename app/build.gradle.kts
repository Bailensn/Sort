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
            // 只出 arm64-v8a：x86_64 只有模拟器用得上，却要让每个 .so 多带一份，
            // APK 直接大十几 MB。以后真要支持模拟器，用下面的 splits 拆成独立包，
            // 不要塞进同一个 APK：
            //   splits { abi { isEnable = true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk = false } }
            abiFilters += listOf("arm64-v8a")
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
// 内置「轻量」模型：medium 下载失败时的兜底，所以 small 必须随 APK 走。
// 下载到 build/generated/ocrModels/models/，由 AGP 当作生成的 assets 目录并入。
// ---------------------------------------------------------------------------
val bundledOcrModels = mapOf(
    "det/inference.onnx" to
        "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx",
    "rec/inference.onnx" to
        "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx",
    "rec/inference.yml" to
        "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.yml",
)

abstract class BundleOcrModelsTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val urls: MapProperty<String, String>

    @TaskAction
    fun download() {
        val root = File(outputDir.get().asFile, "models")
        urls.get().forEach { (relativePath, url) ->
            val target = File(root, relativePath)
            if (target.isFile && target.length() > 1024L) return@forEach
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.part")
            var failure: Exception? = null
            for (attempt in 1..3) {
                try {
                    java.net.URI(url).toURL().openStream().use { input ->
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
                throw GradleException("下载内置模型失败：$url（${failure.message}）")
            }
            if (!temp.renameTo(target)) {
                throw GradleException("无法写入模型文件：${target.absolutePath}")
            }
        }
    }
}

val bundleOcrModels = tasks.register<BundleOcrModelsTask>("bundleOcrModels") {
    group = "ocr"
    description = "下载轻量 OCR 模型，作为 medium 下载失败时的内置兜底"
    outputDir.set(layout.buildDirectory.dir("generated/ocrModels"))
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
