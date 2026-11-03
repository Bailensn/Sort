buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
    }
}

import java.io.File
import java.net.URI
import java.util.zip.ZipFile
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

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
        // ONNX Runtime 要求 26。
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

        externalNativeBuild {
            cmake {
                // MNN 的预编译 .so 由 prepareMnn 下到 build/mnn，这里把它交给 CMake；
                // 预编译包是拿 shared libc++ 编的，必须跟着换，否则加载时报符号缺失。
                arguments += "-DMNN_LIB=${layout.buildDirectory.get().asFile.absolutePath}/mnn/jniLibs/arm64-v8a/libMNN.so"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildTypes {
        release {
            // CI 没配正式签名时，-PciSign 套 debug key，产出的 APK 能直接装上手机测；
            // 配了正式签名就不用它兜底
            if (project.hasProperty("ciSign") && signingConfig == null) {
                signingConfig = signingConfigs.getByName("debug")
            }
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

    // 不能传 Provider：Android 的 SourceSet API 只收字符串或 File
    sourceSets.getByName("main").jniLibs.srcDir(
        "${layout.buildDirectory.get().asFile.absolutePath}/mnn/jniLibs",
    )

    androidResources {
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

dependencies {
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

/**
 * 下载 MNN 的 Android 预编译包，只解出 arm64 的 libMNN.so 到 build/mnn/jniLibs。
 *
 * 顺带把 c++_shared 也放进去？不用——AGP 在 ANDROID_STL=c++_shared 时会自己带。
 */
abstract class PrepareMnnTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val abi: Property<String>

    @TaskAction
    fun fetch() {
        val root = outputDir.get().asFile
        val target = File(root, "jniLibs/${abi.get()}/libMNN.so")
        if (target.isFile && target.length() > 1_000_000L) return

        val zipFile = File(root, "mnn.zip")
        if (!zipFile.isFile || zipFile.length() < 1_000_000L) {
            root.mkdirs()
            val connection = URI(url.get()).toURL().openConnection()
            connection.connectTimeout = 20000
            connection.readTimeout = 120000
            connection.getInputStream().use { input ->
                zipFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        ZipFile(zipFile).use { zip ->
            for (entry in zip.entries()) {
                if (!entry.name.endsWith("/${abi.get()}/libMNN.so")) continue
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                return
            }
        }
        throw IllegalStateException("MNN 包里没有 ${abi.get()}/libMNN.so")
    }
}

val prepareMnn = tasks.register<PrepareMnnTask>("prepareMnn") {
    outputDir.set(layout.buildDirectory.dir("mnn"))
    url.set(
        "https://github.com/alibaba/MNN/releases/download/" +
            "${libs.versions.mnn.get()}/mnn_${libs.versions.mnn.get()}_android_armv7_armv8_cpu_opencl_vulkan.zip",
    )
    abi.set("arm64-v8a")
}

// CMake 配置阶段就要读 MNN_LIB，所以得排在 configureCMake* 之前。
tasks.matching { it.name.startsWith("configureCMake") }.configureEach { dependsOn(prepareMnn) }

// jniLibs 指向 build/mnn，属于任务的产物：合并 native 库前必须先跑下载，
// 否则 Gradle 9 会判「隐式依赖」直接失败。
tasks.matching { it.name.contains("JniLibFolders") }.configureEach { dependsOn(prepareMnn) }
