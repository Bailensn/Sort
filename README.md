# Sort · Jetpack Compose + CMake Native 模板

一个可以直接推到 GitHub 上跑的 Android 工程模板：**Compose (Material 3) + C++/JNI（CMake 构建）+ AGP 9 内置 Kotlin**，自带 GitHub Actions CI。

界面里有一个真实的 JNI 调用：点按钮会走 `Java → JNI → C++ std::sort → Java`，用来验证 native 工具链是否真的通了。

## 版本

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| AGP | **9.4.1** | 最高支持 API 37；最低要求 Gradle 9.6.0 / JDK 17 |
| Gradle | **9.8.0** | 见 `gradle/wrapper/gradle-wrapper.properties`（9.8.2 是同分支最新补丁） |
| Kotlin | **2.4.21** | 用 buildscript classpath 覆盖 AGP 自带的 KGP 2.2.10 |
| JDK | **21** | `compileOptions` 与 Kotlin `jvmTarget` 都是 21 |
| compileSdk / targetSdk | **37** | Android 17 |
| minSdk | 24 | |
| Compose BOM | 2026.09.00 | material3 / ui / ui-test 都由 BOM 管版本 |
| Activity Compose | 1.13.0 | |
| Lifecycle | 2.11.0 | |
| Core KTX | 1.19.1 | |
| NDK | **30.0.16248370 (r30)** | 官方 r30 已把 sysroot 升到 API 37 |
| CMake | **3.22.1** | AGP 9.4 能驱动的最高 SDK CMake 版本 |
| ABI | arm64-v8a、x86_64 | 需要 32 位时自行加回 `armeabi-v7a` / `x86` |

## 目录结构

```
.
├── .github/
│   ├── dependabot.yml              # 每周自动升级 Gradle 依赖 + Actions
│   └── workflows/android.yml       # CI：assemble + test + lint
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro          # 预留 JNI 方法的 keep 规则
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── cpp/
│       │   │   ├── CMakeLists.txt
│       │   │   └── native-lib.cpp  # 产物 libmain.so：greeting / buildInfo / sort
│       │   ├── java/com/bailensn/sort/
│       │   │   ├── MainActivity.kt
│       │   │   ├── NativeBridge.kt # System.loadLibrary("main") + external fun
│       │   │   └── ui/
│       │   │       ├── Format.kt
│       │   │       ├── SortApp.kt
│       │   │       └── theme/{Color,Theme,Type}.kt
│       │   └── res/                # 自适应图标、主题、字符串
│       ├── test/                   # JVM 单测（CI 会跑）
│       └── androidTest/            # Compose 仪器测试（CI 只编译）
├── gradle/
│   ├── libs.versions.toml          # 版本唯一来源
│   └── wrapper/
├── build.gradle.kts                # KGP 版本覆盖 + 插件声明
├── settings.gradle.kts
├── gradle.properties
└── gradlew / gradlew.bat
```

## 几个关键设计

### 1. 没有 `org.jetbrains.kotlin.android`

AGP 9 默认启用「内置 Kotlin」，由 AGP 自己创建 Kotlin 编译任务。再应用 `kotlin-android` 会直接报错，所以 `app/build.gradle.kts` 里只有：

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)   // Compose 编译器插件仍然要，版本跟 Kotlin 对齐
}
```

Kotlin 源码目录、`kotlin { compilerOptions { ... } }` 都由 AGP 提供；`jvmTarget` 默认跟随 `compileOptions.targetCompatibility`，所以设了 Java 21 就不用再单独设 Kotlin。

### 2. Kotlin 2.4.21 是怎么生效的

AGP 9.4.1 的 POM 里对 `org.jetbrains.kotlin:kotlin-gradle-plugin` 有一个 `2.2.10` 的 runtime 依赖。把更高版本放到根工程的 buildscript classpath 上，Gradle 的版本冲突解析会取高版本，AGP 的内置 Kotlin 就会用 2.4.21 的编译器：

```kotlin
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}
```

验证方式：

```bash
./gradlew :app:buildEnvironment | grep kotlin-gradle-plugin
```

### 3. CMake 只能到 3.22.1

SDK 里虽然有 CMake 3.30.3，但 AGP 9.4 的 `CMakeVersion` 枚举只认 `3.6 / 3.10.2 / 3.22.1`，写 3.30.3 会直接找不到。所以 `libs.versions.toml` 里固定 3.22.1，CI 也装这个版本。**不要手改成 3.30.3。**

### 4. JDK 21 与字节码

`compileOptions` 的 source/target 都是 21，Kotlin `jvmTarget` 自动跟随。CI 用 Temurin 21 跑 Gradle；AGP 9.4 的最低要求只是 JDK 17，所以这条链路是安全的。

### 5. native 库名是 `libmain.so`

CMake target 叫 `main`，产物就是 `libmain.so`；Kotlin 侧对应 `System.loadLibrary("main")`。
JNI 符号按包名拼出来，当前是 `Java_com_bailensn_sort_NativeBridge_*`（包名、类名一改，这里必须同步）。

## 本地构建

前提：JDK 21、Android SDK（platform 37.0、build-tools 36.0.0）、NDK r30、CMake 3.22.1。Android Studio 打开工程时会自动提示安装。

```bash
# 首次可能需要
chmod +x gradlew

./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 只跑单测 / lint
./gradlew :app:testDebugUnitTest :app:lintDebug
```

如果 `local.properties` 不存在，Android Studio 会生成；命令行构建也可以用 `ANDROID_HOME` 环境变量。

## CI 做了什么

`.github/workflows/android.yml` 在 `ubuntu-latest` 上：

1. 装 Temurin JDK 21；
2. `android-actions/setup-android` 准备 cmdline-tools 并接受 license；
3. `sdkmanager` 安装 `platforms;android-37.0`、`build-tools;36.0.0`、`cmake;3.22.1`、`ndk;30.0.16248370`；
4. `gradle/actions/setup-gradle` 做依赖缓存；
5. 跑 `assembleDebug`、`assembleRelease`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug`；
6. 上传 APK，失败时额外上传 `reports/` 和 `test-results/`。

想跑真机/模拟器测试，再加一个 `ReactiveCircus/android-emulator-runner` job 调 `connectedDebugAndroidTest` 即可。

> 注意：`gradlew` 的可执行位在共享存储/Windows 上容易丢，所以 workflow 里有一句 `chmod +x ./gradlew` 兜底。本地如果遇到 `Permission denied`，同样 `chmod +x gradlew`，或 `git update-index --chmod=+x gradlew`。

## 改成自己的项目

1. `settings.gradle.kts` 的 `rootProject.name`
2. `app/build.gradle.kts` 的 `namespace` 和 `applicationId`
3. `app/src/main/java/...` 的包目录（test / androidTest 同步）
4. `app/src/main/cpp/native-lib.cpp` 里的 JNI 符号名：`Java_<包名下划线转义>_NativeBridge_<方法名>`
5. `res/values/strings.xml` 的 `app_name`
6. native 库名（可选）：`CMakeLists.txt` 的 target、`NativeBridge.LIBRARY_NAME`（当前是 `main` / `libmain.so`）

JNI 符号和包名是硬绑定的，第 3、4 步必须一起改，否则运行时会 `UnsatisfiedLinkError`。

## 升级清单

- **AGP / Gradle**：一起升。AGP 9.4 要求 Gradle ≥ 9.6.0；升 AGP 前先看它的 compatibility 表。
- **Kotlin**：同步改 `libs.versions.toml` 的 `kotlin`、根 `build.gradle.kts` 的 classpath、以及 Compose 编译器插件版本（后两者由 catalog 引用时只需改一处）。
- **NDK**：`libs.versions.toml` 的 `ndk` 与 workflow 里的 `ndk;...` 一起改。
- **CMake**：在 AGP 支持列表更新前保持 3.22.1。
- **Compose**：只改 BOM 版本，其余 Compose 库不写版本。

## 排查

**CI 报 Compose 编译器与 Kotlin 版本不匹配**：说明那版 AGP 的内置 Kotlin 没接受 2.4.21。临时退回外置 Kotlin（AGP 9.4 仍支持，但 AGP 10 会移除）：

```properties
# gradle.properties
android.builtInKotlin=false
android.newDsl=false
```

然后应用 `org.jetbrains.kotlin.android`（catalog 加插件、根和 app 的 `plugins {}` 各加一行），并删掉根工程的 KGP classpath。

**`Failed to find target with hash string 'android-37'`**：装的是 `platforms;android-37.0`，AGP 9.4 会自己找到 37.0；如果用的是老 AGP，需要把目录软链/复制成 `android-37`。

**找不到 NDK / CMake**：确认 `sdkmanager --list_installed` 里有 `ndk;30.0.16248370` 和 `cmake;3.22.1`；AGP 默认 NDK 是 28.2.13676358，本工程显式覆盖成了 r30。
