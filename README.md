# Sort

拍一张表格照片，或者从相册选一张，**离线**识别文字、还原行列，导出一份带边框的 `.xlsx`。

仓库现在是三端布局：`Android/` 是完整实现，`Native/` 放三端共用的 C++ 核心，`iOS/` 已有 SwiftUI 界面，`Harmony/` 还是占位页。

## 三端现状

| 平台 | 界面 | OCR 引擎 | 状态 |
|---|---|---|---|
| Android | Miuix / Compose，双页签 + 编辑 + 历史 | `libocr.so`（C++ 核心）+ `OcrBridge` + ORT | 界面可用，OCR 待真机验证 |
| iOS | SwiftUI，识别 / 历史双页签 | `OcrBridge.swift` → C++ 核心 | 界面可用，OCR 待接 ORT |
| HarmonyOS | ArkTS 占位页 | `napi_init.cpp` → C++ 核心 | 骨架，MindSpore Lite 待接 |
| Native | — | 整条 OCR 链路已移植成 C++ | 编译通过、假后端跑通，真模型待对拍 |

> Android 已经切到 C++ 核心：`libocr.so` 装 OCR，`libmain.so` 只做 JNI 胶水。
> `libonnxruntime.so` 由 `onnxruntime-android` AAR 打包进 APK，链接用的那份由 `prepareOrt` 解到 `build/ort`。

## 目录结构

```
.
├── .github/
│   ├── scripts/                       # 错误打印、真实姓名检查、SDK 安装
│   └── workflows/                     # android.yml / ios.yml / harmony.yml
├── Android/                           # Android 工程（Gradle 根目录）
│   ├── app/
│   ├── gradle/libs.versions.toml
│   └── build.gradle.kts
├── Native/                            # 三端共用的 C++ 核心
│   ├── capi/                          # C ABI 契约（三端唯一接口）
│   ├── core/ocr/                      # 预处理、DB 后处理、CTC、裁图
│   ├── adapters/onnx/                 # ONNX Runtime 后端
│   ├── tests/                         # 假后端冒烟测试
│   └── tools/ocr_dump/                # 对拍用的命令行
├── iOS/                               # XcodeGen + SwiftUI
│   └── ios-app/
└── Harmony/                           # hvigor 工程
    └── main/src/main/{ets,cpp}/
```

## 版本（Android）

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| AGP | **9.5.0** | 最高支持 API 37；最低要求 Gradle 9.6.0 / JDK 17 |
| Gradle | **9.8.2** | 见 `Android/gradle/wrapper/gradle-wrapper.properties` |
| Kotlin | **2.4.21** | 用 buildscript classpath 覆盖 AGP 自带的 KGP 2.2.10 |
| JDK | **21** | `compileOptions` 与 Kotlin `jvmTarget` 都是 21 |
| compileSdk / targetSdk | **37** | Android 17 |
| minSdk | **26** | 由 ONNX Runtime 决定 |
| Compose BOM | 2026.09.00 | |
| Miuix | **0.9.4** | `miuix-ui` / `miuix-icons` / `miuix-nav` |
| Backdrop | **2.0.1** | 液态玻璃（Android 12 起真模糊，更低的系统退化成半透明） |
| ONNX Runtime | **1.31.1** | OCR 推理 |
| OCR 模型 | PP-OCRv6 medium + PP-OCRv5 server 复核 | det 62 MB、rec 76.5 MB、字符表 150 KB |
| NDK / CMake | **30.0.16248370** / **3.22.1** | C++ 核心编译进 `libmain.so` |
| ABI | arm64-v8a | 模拟器的 x86_64 默认不打 |

## 处理流水线

1. **取图**：系统 Photo Picker（无需存储权限），限制到 2048px，按 EXIF 摆正。
2. **裁切**：Otsu 二值化后按暗像素占比裁掉桌面、黑边、灰底。
3. **微矫正**：±3° 内按 0.25° 步进找最锐利的投影角，再用四条外框线求交点做透视校正；外框线缺失先补虚拟边线。
4. **表格处理**：光照均衡 + CLAHE → 自适应二值化 → 横/纵开运算 → 投影找线带 → 最小二乘拟合斜线 → 相邻线围成单元格。
5. **逐格识别**：先把检测到的表格线抹成纸白（线头会被认成「1」），再按格裁图、**一格一次识别**，不会把「姓名+分数」粘成一个框。四遍递进：去线原图 → 增强灰度 2 倍 → 原图 3 倍 → 白纸黑字二值图，争议格子交给复核模型投票。
6. **回读纠错**：整图再跑一遍检测+识别，检测框按覆盖面积落回单元格，与逐格结果比对。
7. **收尾**：垃圾列裁剪 → 列对齐 → 算术自检（序号、学号连续、单格反推，总分一律按各科重算）→ 数字列修复 → 姓名记忆纠错。
8. **导出**：手写 OOXML（`inlineStr` + 细边框 + 自动列宽），不发明合并单元格；写外部缓存后走系统分享。

第 2–7 步全部在 `Native/` 的 C++ 里跑（`core/imgproc` 做灰度/光照/CLAHE/阈值/旋转，`core/table` 做找线、透视、切格、清洗、自检）。Android 侧只剩把 Bitmap 转成 BGR 缓冲、把结果包成界面的形状，不再有任何表格几何代码，也不再依赖 OpenCV。

## 模型从哪来

模型**不随包发**，首启从分享盘拉，装到 `files/ocr-models/`：

- 主模型 PP-OCRv6 medium（det 62 MB + rec 76.5 MB + 字符表 150 KB）；
- 复核模型 PP-OCRv5 server rec，只在有争议的格子上投票，**下载失败不影响主流程**；
- 两个源：国内走分享盘（`PUT` 换签名直链，约 1 小时过期），备用是
  GitHub **prerelease** `models-v1`（不进「最新版本」，也不占正式发布的位子）；
  每个文件先试主源、不通再吃备用源；
- 下载流程：换签名 → `GET`（带 `Range` 断点续传）→ 比字节数 → 校验 sha256 → 落盘；
  签名过期就重新换，最多重试两轮；传输失败保留 `.part`，换源也能接着下；
- 每个文件的字节数与 sha256 钉在 `ModelInstaller` 里，和 `tools/models.lock` 一致；
  校验不过宁可报错，也不拿半截模型去识别；
- 首启需要联网约 220 MB；
- GitHub 备用源由 `.github/workflows/models.yml` 发布：手动跑一次（或推 `models-*` 标签），
  它从官方仓库下载、按 `tools/models.lock` 校验后挂到固定的 prerelease
  「tag `models-v1` / 名称 `OCR Models`」上；触发方式怎么变都只更新这一个 release。
  用 prerelease 而不是普通 release，是为了不顶掉正式版本的「最新」标记；
  也不能用 draft，草稿的资源匿名下不到。

## 构建

**Android**（Gradle 根在 `Android/`）：

```bash
cd Android
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease    # R8 + 资源压缩
./gradlew :app:bundleRelease      # 商店用 aab
./gradlew :app:testDebugUnitTest :app:lintDebug
```

**Native 核心**（不需要任何平台 SDK）：

```bash
cmake -S Native -B Native/build -DSORT_BUILD_TESTS=ON -DSORT_BUILD_TOOLS=ON
cmake --build Native/build
ctest --test-dir Native/build --output-on-failure
```

**iOS**（需要 macOS）：

```bash
bash tools/fetch_ort_ios.sh          # 下 ONNX Runtime xcframework 到 ios-app/Vendor/（62 MB，不入库）
cd iOS/ios-app
xcodegen generate
xcodebuild -project Sort.xcodeproj -scheme Sort -configuration Release build
```

ORT 取自官方 pod 包（`pod-archive-onnxruntime-c-<version>.zip`），里面有 `ios-arm64` 和
`ios-arm64_x86_64-simulator` 两个 slice，模拟器和真机都能链。

**HarmonyOS**（需要 DevEco 工具链）：

```bash
cd Harmony
hvigorw assembleHap -p buildMode=release   # 单模块 hap
hvigorw assembleApp -p buildMode=release   # 整包 app
```

模型在 `rawfile/models/models.zip`（三个文件压成一个，约 40 MB），首启由 ArkTS 解到沙箱。

## CI

三个 workflow，各自独立：

| 文件 | 触发 | job | 跑什么 |
| --- | --- | --- | --- |
| `android.yml` | 推送自动（`Android/**`、`Native/**`、`tools/**`） | `native-core` | `cmake` 编译 + `ctest` 冒烟测试 + 用 `gcc` 验证契约头是纯 C |
| | | `assemble-release` / `lint` / `unit-test` / `android-test` | Gradle 四个任务，release 出 apk + aab |
| `ios.yml` | 手动 | `build` | `xcodegen` + 模拟器 Release + 无签名 ipa |
| `harmony.yml` | 手动 | `models` / `build` | ONNX→MNN 转换；`hvigorw assembleHap` + `assembleApp`（DevEco 容器镜像） |

Android 部分：顶层 `defaults.run.working-directory: Android`，`./gradlew` 与 `build.log` 都落在 `Android/`；artifact 和缓存这类不经过 `run` 的路径显式写 `Android/app/build/...`。SDK 组件安装抽成 `.github/scripts/install-sdk-components.sh`，四个 job 共用。失败时 `print-errors.sh` 直接把 Kotlin/Java 错误和 `What went wrong` 打在同一个 step 里，带 `::error file=...,line=...` 注解。

鸿蒙跑在 `ghcr.io/sanchuanhehe/harmony-next-pipeline-docker` 镜像里，容器自带 DevEco 命令行工具。

## 没有真机怎么验

- **iOS**：`test` job 在 macOS 模拟器里跑 `OcrPipelineTests`，用真模型识别
  `Tests/Fixtures/table.png`（`tools/make_fixture.py` 生成的点阵数字表格），
  断言能读回 `900000000001`。模型解压、ORT 前向、DB 后处理、CTC 解码全部走真代码。
- **Android**：`unit-test` 跑 JVM 测试，OCR 本身要真机或模拟器。
- **鸿蒙**：只能在 `build` job 里编译 + 用 `nm` 检查符号；运行时得等真机。

## 已知限制

- **不做合并单元格判断**：每个格子独立识别，线没画全就拆成两格，跨列标题会按格拆开；
- 导出的 xlsx 不写 `<mergeCells>`；
- 完全没框线的表格走文字聚类，整列为空时列会丢；
- 逐格识别假设单元格内是单行文字，多行会被压成一行；
- 竖排、旋转拍摄、手写体识别率有限；
- 透视校正需要四条外框线都可见，补不出来的只做旋转纠偏；
- 自检只覆盖「序号/学号连续」「科目求和」，结构里没有这两类约束的表只能报「没有可用的约束」；
- 部分科目缺考时总分按**已考科目**补算并注明；印出来的总分对不上只标存疑，不照着求和改分；
- C++ 版的 DB 后处理只追踪外轮廓、孔洞用洪泛补，没有完全复刻 OpenCV 的 `RETR_LIST`；
- `ocr_dump` 只认 PPM/PGM，不做 JPEG/PNG 解码；
- 鸿蒙界面还是占位页，只有原生桥接了 OCR 核心。

## 改名 / 升级

- 包名：Android `namespace`/`applicationId`、iOS `PRODUCT_BUNDLE_IDENTIFIER`、鸿蒙 `bundleName` 三处一起改；
- native 库名：`add_library(main ...)` 与各端加载的名字（`System.loadLibrary("main")` / `nm_modname`）必须一致；
- JNI 符号跟着 Kotlin 包名走，`jni_engine.cpp` 里的 `Java_...` 要同步；
- AGP / Gradle 一起升，AGP 9.5 要求 Gradle ≥ 9.6.0；
- ONNX Runtime 升大版本前确认 Android 的 `OrtSession` 与 C++ 的 `Ort::Session` 接口都没变。

## 许可证

本项目采用 **Apache License 2.0**（SPDX：`Apache-2.0`），全文见 [LICENSE](LICENSE)。

选它的理由：OCR、表格线拟合、透视校正容易踩算法专利，Apache-2.0 第 3 条给了明确的专利许可；
PaddleOCR、OpenCV、AndroidX/Compose、Miuix 都是 Apache-2.0、ONNX Runtime 是 MIT，可以一起分发；
允许闭源和商用，只要保留版权与 NOTICE。

第三方组件与各自的许可见 [NOTICE](NOTICE)。
