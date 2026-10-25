# 表格转 Excel · Compose + PP-OCRv6 + CMake Native

拍一张表格照片，或者从相册选一张，**离线**识别文字、还原行列，导出一份带边框的 `.xlsx`。

- OCR：PaddleOCR **PP-OCRv6 small**（ONNX Runtime，端上推理，不联网）
- 微矫正：投影估角纠偏 + 外框四点透视校正
- 表格处理：拟合横竖线、切单元格、逐格识别，避免「姓名+分数」粘成一个检测框
- 回读纠错：再跑一遍整图检测，按覆盖面积落回单元格，和逐格结果交叉校对
- 导出：手写 OOXML（不引 Apache POI），细边框、自动换行、按内容估宽
- 分享：`FileProvider` + `ACTION_SEND`
- 保留 C++ `libmain.so`（CMake/JNI 模板）

## 版本

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| AGP | **9.4.1** | 最高支持 API 37；最低要求 Gradle 9.6.0 / JDK 17 |
| Gradle | **9.8.2** | 见 `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin | **2.4.21** | 用 buildscript classpath 覆盖 AGP 自带的 KGP 2.2.10 |
| JDK | **21** | `compileOptions` 与 Kotlin `jvmTarget` 都是 21 |
| compileSdk / targetSdk | **37** | Android 17 |
| minSdk | **26** | 由 ONNX Runtime 决定 |
| Compose BOM | 2026.09.00 | |
| Activity Compose | 1.13.0 | Photo Picker |
| Lifecycle | 2.11.0 | ViewModel + `collectAsStateWithLifecycle` |
| ONNX Runtime | **1.31.0** | OCR 推理 |
| OpenCV | **4.14.0** | 纠偏、表格线、透视变换 |
| OCR 模型 | PP-OCRv6 small | det 约 9.9 MB，rec 约 21 MB |
| NDK | **30.0.16248370 (r30)** | sysroot 到 API 37 |
| CMake | **3.22.1** | AGP 9.4 能驱动的最高 SDK CMake 版本 |
| ABI | arm64-v8a、x86_64 | 需要 32 位时自行加回 |

## 目录结构

```
.
├── .github/
│   ├── dependabot.yml
│   └── workflows/android.yml
├── app/
│   ├── build.gradle.kts               # 模型下载、CMake、依赖
│   └── src/
│       ├── main/
│       │   ├── cpp/                   # libmain.so（C++23 / JNI 模板）
│       │   ├── java/com/bailensn/sort/
│       │   │   ├── MainActivity.kt
│       │   │   ├── NativeBridge.kt
│       │   │   ├── table/
│       │   │   │   ├── Deskewer.kt              # 投影估角 + 旋转
│       │   │   │   ├── TableLineDetector.kt     # 带斜率的线拟合
│       │   │   │   ├── PerspectiveRectifier.kt # 四点透视校正
│       │   │   │   ├── CellGrid.kt              # 线 → 单元格
│       │   │   │   ├── TableGridBuilder.kt      # 纯 Kotlin 表格重建
│       │   │   │   ├── GridOverlay.kt           # 网格叠加图
│       │   │   │   └── TablePipeline.kt         # 完整流水线 + 回读纠错
│       │   │   ├── ui/table/                    # 界面 + ViewModel
│       │   │   ├── ui/theme/
│       │   │   ├── util/ImageLoader.kt
│       │   │   └── xlsx/                        # XlsxWriter / XlsxExporter
│       │   └── res/
│       ├── test/                      # 纯 JVM 单测（CI 会跑）
│       └── androidTest/
├── ppocr-sdk/                         # 从 ppocr-android 融合的 OCR SDK
├── gradle/libs.versions.toml
├── build.gradle.kts
└── settings.gradle.kts
```

## 处理流水线

1. **取图**：系统 Photo Picker（无需存储权限），限制到 2048px，按 EXIF 摆正。
2. **微矫正**：
   - 二值图在 ±3° 内按 0.25° 步进旋转，统计行投影的跳变能量，取最锐利的角度；
   - 旋转后用拟合出的四条最外层表格线求交点，做透视变换，把表格拉成正矩形。
3. **表格处理**：自适应二值化 → 横/纵开运算 → 投影找线带 → 每个线带按窗口取质心、最小二乘拟合出真实斜线。
4. **逐格识别**：相邻线围成单元格，按格裁图（去边框、补白边），**一格一次识别**，不会出现跨列合并框。
5. **回读纠错**：整图再跑一次检测+识别，每个检测框按 75% 覆盖面积落回单元格，和逐格结果比对；不一致时按置信度取舍，并统计纠正数量。
6. **导出**：`XlsxWriter` 写 OOXML zip（`inlineStr` + 细边框 + 自动列宽），`XlsxExporter` 写到缓存目录后走系统分享。

## 界面与交互

界面按四套自研设计 SKILL 的约束实现：

- 移动端：状态栏/手势条安全区、48dp 最小触控热区、底部全宽主操作、真实阶段进度；
- B 端：单一主操作、0.5dp 发丝边框、同心圆角（16 外层 / 8 内层）；
- 桌面 Agent：字号收敛为 20/15/13 三档、正文最小 13sp、禁用「·」中点分隔符、去掉说明式冗余文案；
- 动效：仅用 `animateContentSize` 做布局过渡，不做无意义动画。

结果页支持「看原图 / 看网格」切换，绿线是实际参与切格的拟合线，可以直接对照原图检查。

## 模型从哪来

模型不进 git（约 31 MB）。`app/build.gradle.kts` 里的 `downloadOcrModels`：

- 从 HuggingFace 下载 `PP-OCRv6_small_{det,rec}_onnx` 的 `inference.onnx` / `inference.yml`；
- 落到 `app/src/main/assets/models/{det,rec}/`，已存在就跳过，失败重试 3 次；
- `merge*Assets` 依赖它，第一次构建会联网下载；
- CI 用 `actions/cache` 缓存该目录。

换模型改 `ocrModels` 映射即可，det/rec 各要 `.onnx`，rec 还要 `inference.yml`（字符表）。

## 构建

```bash
# 首次会下载模型（约 31 MB）
./gradlew :app:assembleDebug

# 单测 / lint
./gradlew :app:testDebugUnitTest :app:lintDebug
```

## CI

`.github/workflows/android.yml`：JDK 21 → setup-android → 装 `platforms;android-37.0`、`build-tools;36.0.0`、`cmake;3.22.1`、`ndk;30.0.16248370` → 缓存模型 → `assembleDebug`、`assembleRelease`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` → 上传 APK 与失败报告。

构建失败时会自动输出错误，不用翻几万行日志：

- **Job Summary**：Kotlin/Java 编译错误、失败 Task、Gradle 失败原因、日志末尾；
- **提交评论**：同一份报告自动贴到本次提交，公开仓库可直接读取（需要 `contents: write`，不需要就删掉这一步和权限）；
- **行内注解**：编译错误生成 `::error file=...,line=...`，PR/commit 页面能直接点；
- **artifact**：完整 `build.log` 与 `ci-report/errors.md` 保留 7 天。

> `gradlew` 的可执行位在共享存储/Windows 上容易丢，workflow 里有 `chmod +x ./gradlew` 兜底。

## 已知限制

- 合并单元格会被拆成多个格子，导出不做 merge；
- 逐格识别假设单元格内是**单行**文字，多行会被压成一行（无框线时会退回整图检测 + 聚类）；
- 竖排、旋转拍摄、手写体识别率有限；
- 透视校正需要四条外框线都可见，缺边时只做旋转纠偏。

## 改名 / 升级

- 包名：`namespace` / `applicationId` / 源码目录 / JNI 符号 `Java_com_bailensn_sort_NativeBridge_*` 一起改；
- native 库名：`CMakeLists.txt` 的 target（`main` → `libmain.so`）与 `NativeBridge.LIBRARY_NAME`；
- Kotlin：同时改 `libs.versions.toml` 的 `kotlin` 和根 `build.gradle.kts` 的 classpath；
- AGP / Gradle：一起升，AGP 9.4 要求 Gradle ≥ 9.6.0；
- OpenCV / ONNX Runtime：升大版本前确认 `Utils.bitmapToMat`、`OrtSession` 等 API 未变。
