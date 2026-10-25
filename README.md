# Sort · Compose + PP-OCRv6 + CMake Native

拍一张表格照片，或者从相册选一张，**离线**识别文字、还原行列，导出一份带边框的 `.xlsx`。

- OCR：PaddleOCR **PP-OCRv6 small**（ONNX Runtime，端上推理，不联网）
- 微矫正：投影估角纠偏 + 外框四点透视校正
- 表格处理：拟合横竖线、切单元格、逐格识别，避免「姓名+分数」粘成一个检测框
- 回读纠错：再跑一遍整图检测，按覆盖面积落回单元格，和逐格结果交叉校对
- 抗噪：裁格按实测线宽内缩，表格线不会残留成「1」；单元格文本统一去线头、去汉字间空格；低置信度格子换尺度重认一次
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

模型**不内嵌 APK**，首次使用时下载到内部存储（`files/ocr-models/`），之后完全离线：

- 默认下 **PP-OCRv6 medium**（det 62 MB + rec 76 MB + 字符表 150 KB），比 small 明显准；
- medium 失败（没网、空间不足、镜像挂）自动退回 **small**（约 31 MB），保证能用；
- 下载前用字符表做一次测速，**国内 `hf-mirror.com` 与海外 `huggingface.co` 谁快用谁**，
  下载中途失败会自动换另一个镜像重试；
- 下载有进度（百分比 + MB/s），完成后不再联网。

换模型改 `ModelRepository` 里的 `Profile` 即可；`ModelRepository.clear(context)` 可清掉已下载的模型。

## 构建

```bash
# 模型在首次运行时下载，构建不再联网
./gradlew :app:assembleDebug

# 单测 / lint
./gradlew :app:testDebugUnitTest :app:lintDebug
```

## CI

`.github/workflows/android.yml` 拆成 5 个互不依赖的并行 job：

| job | 跑什么 |
| --- | --- |
| `assemble-debug` | `assembleDebug`，成功时上传 `apk-debug` |
| `assemble-release` | `assembleRelease`，成功时上传 `apk-release` |
| `lint` | `lintDebug` |
| `unit-test` | `testDebugUnitTest` |
| `android-test` | `assembleDebugAndroidTest`（只编译，不跑） |

每个 job 开头各自跑一遍公共环境（JDK 21、Android SDK、Gradle、SDK 组件、模型缓存），
没有抽成 composite action——这样哪一步挂掉，run 页面的注解会直接点名是哪一步，
而不是笼统地报在 "Android environment" 上。NDK 与 CMake 只装进需要它们的两个 job。

失败时不用下载任何东西：构建步骤会在同一个 step 里直接把错误打出来——

- `::group::构建错误` 里是 Kotlin/Java 错误、失败的 Task、Gradle 的 `What went wrong`；
- 带文件行号的错误同时生成 `::error file=...,line=...` 注解，run 页面和 PR 页面能直接点；
- 解析不到结构化错误时，退化成打印 `What went wrong` 和日志末尾 30 行。

实现是一个 30 行的脚本 `.github/scripts/print-errors.sh`，四个 job 的 gradle 步骤失败时调用它，
不写评论、不需要 token、不需要 artifact。

> `gradlew` 的可执行位在共享存储/Windows 上容易丢，每个 job 里都有
> `chmod +x ./gradlew` 兜底。

## 已知限制

- 合并单元格靠「两格之间那条边界有没有画线」判断：线没画全、或线太淡导致探针判不到，
  会退化成多个格子（判定偏保守，宁可拆开也不误合并），非矩形的合并区域一律拆开；
- 完全没框线的表格走文字聚类，列位置靠文字间距推断，整列为空时列会丢；
- 逐格识别假设单元格内是**单行**文字，多行会被压成一行（无框线时会退回整图检测 + 聚类）；
- 竖排、旋转拍摄、手写体识别率有限；
- 透视校正需要四条外框线都可见，缺边时只做旋转纠偏。

## 改名 / 升级

- 包名：`namespace` / `applicationId` / 源码目录 / JNI 符号 `Java_com_bailensn_sort_NativeBridge_*` 一起改；
- native 库名：`CMakeLists.txt` 的 target（`main` → `libmain.so`）与 `NativeBridge.LIBRARY_NAME`；
- Kotlin：同时改 `libs.versions.toml` 的 `kotlin` 和根 `build.gradle.kts` 的 classpath；
- AGP / Gradle：一起升，AGP 9.4 要求 Gradle ≥ 9.6.0；
- OpenCV / ONNX Runtime：升大版本前确认 `Utils.bitmapToMat`、`OrtSession` 等 API 未变。

## 许可证

本项目采用 **Apache License 2.0**（SPDX：`Apache-2.0`），全文见 [LICENSE](LICENSE)。

选它的理由：

- **专利授权**：OCR、表格线拟合、透视校正这类东西容易踩到算法专利，Apache-2.0 第 3 条给了
  明确的专利许可，MIT 没有；
- **与依赖一致**：PaddleOCR、OpenCV、AndroidX/Compose 都是 Apache-2.0，ONNX Runtime 是 MIT，
  Apache-2.0 可以把它们一起分发，不用做额外豁免；
- **商用友好**：允许闭源分发和商用，只要求保留版权与 NOTICE，比 GPL 更适合一个要发给同学用的工具。

> 想要最宽松、最短的协议就换 MIT；想让所有衍生作品必须开源就换 GPL-3.0。
> 换之前记得同步 `LICENSE` 和本段。

第三方组件与各自的许可见 [NOTICE](NOTICE)。
