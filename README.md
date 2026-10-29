# Sort · Miuix + PP-OCRv6 + CMake Native

拍一张表格照片，或者从相册选一张，**离线**识别文字、还原行列，导出一份带边框的 `.xlsx`。

- OCR：PaddleOCR **PP-OCRv6 medium** 主认 + **PP-OCRv5 server** 复核（ONNX Runtime，端上推理，不联网）
- 界面：[Miuix](https://github.com/compose-miuix-ui/miuix) 0.9.4，不引 Material3
- 微矫正：投影估角纠偏 + 外框四点透视校正
- 表格处理：拟合横竖线、切单元格、逐格识别，避免「姓名+分数」粘成一个检测框
- 回读纠错：原图 / 增强图 / 二值图多遍识别，两套模型投票，低置信度格子再认一次
- 算术自检：序号与学号连续性、科目列求和校验、单格反推纠正，仍对不上的行标成存疑
- 抗噪：裁格按实测线宽内缩，表格线不会残留成「1」；单元格文本统一去线头、去汉字间空格；末尾垃圾列按「线头字符 / 字母占比」整列裁掉
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
| Miuix | **0.9.4** | `miuix-ui` / `miuix-icons` / `miuix-nav` |
| Activity Compose | 1.13.0 | Photo Picker |
| Lifecycle | 2.11.0 | ViewModel + `collectAsStateWithLifecycle` |
| ONNX Runtime | **1.31.0** | OCR 推理 |
| OpenCV | **5.0.0.1** | 纠偏、表格线、透视变换（几何 API 挪到 `org.opencv.geometry`） |
| OCR 模型 | PP-OCRv6 medium + PP-OCRv5 server 复核 | det 62 MB、rec 76.5 MB、字符表 150 KB |
| NDK | **30.0.16248370 (r30)** | sysroot 到 API 37 |
| CMake | **3.22.1** | AGP 9.4 能驱动的最高 SDK CMake 版本 |
| ABI | arm64-v8a | 模拟器用的 x86_64 默认不打，需要时用 ABI splits 出独立包 |

## 目录结构

```
.
├── .github/
│   ├── dependabot.yml
│   ├── scripts/                       # 错误打印、真实姓名检查
│   └── workflows/android.yml
├── app/
│   ├── build.gradle.kts               # 模型下载、CMake、依赖
│   └── src/
│       ├── main/
│       │   ├── cpp/                   # libmain.so（C++23 / JNI 模板）
│       │   ├── java/com/bailensn/sort/
│       │   │   ├── MainActivity.kt
│       │   │   ├── NativeBridge.kt
│       │   │   ├── data/                        # HistoryStore / NameMemory
│       │   │   ├── ocr/ModelInstaller.kt        # 内置模型解压
│       │   │   ├── table/
│       │   │   │   ├── ContentCropper.kt        # 裁到有内容的那块
│       │   │   │   ├── Deskewer.kt              # 投影估角 + 旋转
│       │   │   │   ├── PerspectiveRectifier.kt  # 四点透视校正
│       │   │   │   ├── GrayPreprocessor.kt      # 光照均衡 + CLAHE
│       │   │   │   ├── TableLineDetector.kt     # 带斜率的线拟合
│       │   │   │   ├── TableEdgeLines.kt        # 补虚拟外框线
│       │   │   │   ├── CellGrid.kt              # 线 → 单元格
│       │   │   │   ├── PixelBorderProbe.kt      # 像素级并格判断
│       │   │   │   ├── GarbageColumnTrimmer.kt  # 垃圾列裁剪
│       │   │   │   ├── NumericColumnRepair.kt   # 数字列修复
│       │   │   │   ├── TableSelfCheck.kt        # 序号/求和自检与反推
│       │   │   │   ├── TableGridBuilder.kt      # 纯 Kotlin 表格重建
│       │   │   │   ├── GridOverlay.kt           # 网格叠加图
│       │   │   │   └── TablePipeline.kt         # 完整流水线 + 多遍识别
│       │   │   ├── ui/
│       │   │   │   ├── table/
│       │   │   │   │   ├── TableScreen.kt       # 页面栈 + 页签外壳 + 浮层
│       │   │   │   │   ├── RecognizePage.kt     # 识别页
│       │   │   │   │   ├── HistoryPage.kt       # 历史页
│       │   │   │   │   └── TableViewModel.kt
│       │   │   │   └── theme/Theme.kt           # MiuixTheme
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
2. **裁切**：Otsu 二值化后按暗像素占比裁掉桌面、黑边、灰底，后面的步骤只处理表格本身。
3. **微矫正**：
   - 二值图在 ±3° 内按 0.25° 步进旋转，统计行投影的跳变能量，取最锐利的角度；
   - 旋转后用拟合出的四条最外层表格线求交点，做透视变换，把表格拉成正矩形；外框线缺失时先补虚拟边线再校正。
4. **表格处理**：光照均衡 + CLAHE → 自适应二值化 → 横/纵开运算 → 投影找线带 → 每个线带按窗口取质心、最小二乘拟合出真实斜线 → 相邻线围成单元格。
5. **逐格识别**：按格裁图（去边框、补白边），**一格一次识别**，不会出现跨列合并框。四遍递进：
   - 一遍：原图 ×1，所有格子；
   - 二遍：增强图，所有格子；争议格子再交给 PP-OCRv5 server 复核；
   - 三遍：仍低于 98% 的格子，原图放大重认 + 复核模型再投一次；
   - 四遍：低于 60% 的，换二值图再认。
6. **回读纠错**：整图再跑一次检测+识别，每个检测框按 75% 覆盖面积落回单元格，和逐格结果比对；不一致时按置信度取舍，并统计纠正数量。
7. **收尾**：裁掉线头/字母垃圾列 → 对齐裁剪后的列 → 算术自检（序号、学号、求和、单格反推）→ 数字列修复 → 姓名记忆纠错 → 归一化。
8. **导出**：`XlsxWriter` 写 OOXML zip（`inlineStr` + 细边框 + 自动列宽），`XlsxExporter` 写到缓存目录后走系统分享。

## 界面与交互

全部用 Miuix 的组件，没有 Material3，也没有自绘配色：

- 两个页签（识别 / 历史）放在 `HorizontalPager` 里，`NavigationBar` 的点按和左右滑动共用同一份状态，
  切页保留各自的滚动位置；
- 顶部 `SmallTopAppBar`；分节用 `SmallTitle`，内容用 `Card` + `BasicComponent`，
  统计和历史条目都是等高行，不会因为文字长短忽大忽小；
- 全屏看图是 `miuix-nav` 栈内的页面：底部上滑进场，下滑手势或系统返回退栈；
- 单元格编辑是 `OverlayBottomSheet`，删除确认是 `OverlayDialog`，操作结果走 `Scaffold` 的 Snackbar；
- 返回键按「编辑弹层 → 删除确认 → 回到识别页 → 退出」的顺序收；
- 历史记录一条一个文件夹：`rows.tsv` + `original.jpg` / `grid.jpg` / `enhanced.jpg`，
  打开历史同样能在三种视图间切换（图统一缩到 1600px，最多留 20 条）；
- 尺寸固定：表格行高 40dp、预览图 4:3、进度圈 22dp、按钮整行 40dp。

识别页可以切「看网格 / 看原图 / 看增强」，绿线是实际参与切格的拟合线，可以直接对照原图检查。

## 模型从哪来

模型**内置在 APK 里**，运行时不再联网下载：

- 主模型 **PP-OCRv6 medium**（det 62 MB + rec 76.5 MB + 字符表 150 KB），比 small 准得多；
- 复核模型 **PP-OCRv5 server rec**，比 v6 medium 更大，只在有争议的格子上投票；
- 构建期从 ModelScope / HuggingFace 下载原始模型，再用 **XZ（LZMA2 预设 9e）** 压成 `.xz` 放进 assets，
  首次启动解压到 `files/ocr-models/`，之后直接用文件加载；
- 解压只做一次，带版本号（`medium-xz-2`），模型换了会自动重解压；复核模型是可选的，缺了只是少一路投票；
- 实测压缩结果：det 62 MB → 32 MB（52%），rec 76.5 MB → 66 MB（86%），字符表 150 KB → 8 KB；
  `.xz` 设置了 `noCompress`，aapt 不会重复压。

首次构建会下载约 140 MB 模型并压缩（本机约 4 分钟，XZ 9 预设比较吃 CPU），CI 用 `actions/cache` 缓存压缩产物。

## 构建

```bash
# 模型随 APK 内置，构建时下载并压缩一次
./gradlew :app:assembleDebug

# release 会做 R8 + 资源压缩
./gradlew :app:assembleRelease

# 单测 / lint
./gradlew :app:testDebugUnitTest :app:lintDebug
```

## CI

`.github/workflows/android.yml` 拆成 4 个互不依赖的并行 job：

| job | 跑什么 |
| --- | --- |
| `assemble-release` | `assembleRelease`，成功时上传 `apk-release` |
| `lint` | `lintDebug`，有 warning/error 才上传报告 |
| `unit-test` | `testDebugUnitTest` |
| `android-test` | `assembleDebugAndroidTest`（只编译，不跑） |

每个 job 开头各自跑一遍公共环境（JDK 21、Android SDK、Gradle、SDK 组件、模型缓存），
没有抽成 composite action——这样哪一步挂掉，run 页面的注解会直接点名是哪一步，
而不是笼统地报在 "Android environment" 上。NDK 与 CMake 只装进需要它们的 job。

缓存（每次跑 CI 直接复用，省几分钟）：

- `gradle/actions/setup-gradle` 缓存 Gradle Home（依赖、wrapper、构建缓存），并开启每周清理；
- `Cache bundled OCR models` 缓存构建期下载并压缩好的内置模型；
- Gradle 命令统一带 `--build-cache`，配置缓存由 `gradle.properties` 打开。

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
- 透视校正需要四条外框线都可见（缺失时会补虚拟边线），补不出来的只做旋转纠偏；
- 自检只覆盖「序号/学号连续」「科目求和」两类约束，结构里没有这两类的表格，自检只能说「没有可用的约束」。

## 改名 / 升级

- 包名：`namespace` / `applicationId` / 源码目录 / JNI 符号 `Java_com_bailensn_sort_NativeBridge_*` 一起改；
- native 库名：`CMakeLists.txt` 的 target（`main` → `libmain.so`）与 `NativeBridge.LIBRARY_NAME`；
- Kotlin：同时改 `libs.versions.toml` 的 `kotlin` 和根 `build.gradle.kts` 的 classpath；
- AGP / Gradle：一起升，AGP 9.4 要求 Gradle ≥ 9.6.0；
- OpenCV：5.x 之后几何 API 在 `org.opencv.geometry`，native 库名是 `libopencv_java5.so`；
- ONNX Runtime：升大版本前确认 `Utils.bitmapToMat`、`OrtSession` 等 API 未变。

## 许可证

本项目采用 **Apache License 2.0**（SPDX：`Apache-2.0`），全文见 [LICENSE](LICENSE)。

选它的理由：

- **专利授权**：OCR、表格线拟合、透视校正这类东西容易踩到算法专利，Apache-2.0 第 3 条给了
  明确的专利许可，MIT 没有；
- **与依赖一致**：PaddleOCR、OpenCV、AndroidX/Compose、Miuix 都是 Apache-2.0，ONNX Runtime 是 MIT，
  Apache-2.0 可以把它们一起分发，不用做额外豁免；
- **商用友好**：允许闭源分发和商用，只要求保留版权与 NOTICE，比 GPL 更适合一个要发给同学用的工具。

> 想要最宽松、最短的协议就换 MIT；想让所有衍生作品必须开源就换 GPL-3.0。
> 换之前记得同步 `LICENSE` 和本段。

第三方组件与各自的许可见 [NOTICE](NOTICE)。
