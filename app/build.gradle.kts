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
