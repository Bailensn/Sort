# Native

三端共用的 C++ 核心。平台侧只做两件事：把图转成缓冲、把模型路径传进来。

```
Native/
├── CMakeLists.txt          # 一个根，产出 ocr_core / 两个测试 / ocr_dump
├── capi/                   # C ABI 契约（唯一需要三端都认识的头）
│   ├── engine_errors.h     # 共用返回码
│   └── engine_ocr_capi.h   # OCR + 整表分析 + 纯逻辑（认表头/自检）
├── core/ocr/               # det/rec 预处理、DB 后处理、CTC 解码、四点裁图
├── core/imgproc/           # 灰度、光照压平、CLAHE、阈值/Otsu、缩放、旋转、裁切
├── core/table/             # 裁内容、纠偏、找线、透视、切格、清洗、表头、自检、叠线
├── adapters/
│   ├── BackendFactory.*    # 按开关挑后端
│   ├── onnx/               # ONNX Runtime（.onnx），SORT_WITH_ONNXRUNTIME
│   ├── mnn/                # MNN（.mnn），鸿蒙走这条路
│   └── paddle_lite/        # Paddle-Lite（.nb），SORT_WITH_PADDLE_LITE
├── tests/ocr_smoke.cpp     # 假后端跑全链路，不需要模型
├── tests/table_logic_test.cpp # 自检/表头/修数/垃圾列/网格
├── tests/table_image_test.cpp # 合成表格图：裁切、纠偏、找线、外框、叠线
└── tools/ocr_dump/         # 对拍用的命令行，输入 PPM/PGM，输出 JSON
```

## 契约

`capi/*.h` 只有 C 声明 + `extern "C"`，能被 C / ObjC / C++ / Swift 桥接头直接 include：

- 不透明句柄（`EngineHandle`、`OcrHandle`），C++ 类型不外露；
- 字符串和结果都走回调；只有处理后的位图由库分配，配 `engine_table_image_free` 释放；
- 错误一律返回码，异常在 `.cpp` 里被 `catch` 掉。

-1..-4 在 `engine_errors.h`，-5/-6 是 OCR 专用的（模型打不开、后端没编进来）。

## OCR 参数（与 Android 侧 `PaddleOCRConfig` 逐项一致）

| 项 | 值 |
|---|---|
| det 输入 | 长边按 `limitSideLen=64`、`limitType=min` 缩放，再补到 32 的倍数 |
| det 归一化 | 转 RGB，`/255` 后减 `0.485/0.456/0.406`，除 `0.229/0.224/0.225` |
| det 后处理 | `thresh 0.3`、`boxThresh 0.6`、`unclip 1.5`、`maxCandidates 3000`、不做膨胀 |
| rec 输入 | 每张裁图缩到高 48、宽按长宽比（上限 3200），右侧补 0 |
| rec 归一化 | `/127.5 - 1` |
| 解码 | CTC 贪心，blank=0，字符索引减一，置信度取保留帧均值 |
| 格子 | 内缩 `max(2, min(w,h)/40)`；高 <28 放大 1.5~3 倍；四周补白 `max(4, 高/8)`；空白格跳过 |

## 本地构建

不装任何平台 SDK 也能编核心（ORT 缺席时后端退化成空实现）：

```bash
cmake -B build -DSORT_BUILD_TESTS=ON -DSORT_BUILD_TOOLS=ON
cmake --build build
ctest --test-dir build --output-on-failure
```

不方便用 CMake 时，直接编：

```bash
clang++ -std=c++17 -Icore -Icore/ocr -Iadapters/onnx \
  core/ocr/*.cpp tests/ocr_smoke.cpp -o ocr_smoke && ./ocr_smoke
```

## 各端怎么接

**Android** —— `Android/app/src/main/cpp/CMakeLists.txt` 里 `add_subdirectory` 到本目录，
OCR 编成 `libocr.so`，`libmain.so` 只留 JNI 胶水并链接它；Kotlin 侧是 `OcrBridge.kt`。
ORT 的头文件和 `.so` 由 Gradle 的 `prepareOrt` 从 AAR 解到 `build/ort`，再用 `-DORT_ROOT` 传给 CMake。

**iOS** —— `ios-app/project.yml` 的 `sources` 直接引 `../../Native/{core,capi,adapters/onnx}`，
Swift 通过 bridging header 调 C 函数（`OcrBridge.swift`）；ORT 用 `onnxruntime.xcframework`，
放进 `ios-app/Vendor/` 后在 `project.yml` 里取消注释。

**HarmonyOS** —— `Harmony/main/src/main/cpp/CMakeLists.txt` 同样 `add_subdirectory` 到本目录，
NAPI 胶水在 `napi_init.cpp`；走 MNN（`SORT_WITH_MNN`），`libMNN.so` 由 `tools/fetch_mnn.sh` 拉。

> 改了 `Native/CMakeLists.txt` 要当心：`ORT_ROOT` 那段只有安卓会走，主机上的 CI 配置不到，
> 只改一处就可能在安卓上 configure 失败。另外同一个目标的所有 `target_link_libraries`
> 必须用同一种签名（全带 `PUBLIC/PRIVATE` 或全不带）。

## 已知限制

- 推理后端优先级 `SORT_WITH_MNN` > `SORT_WITH_PADDLE_LITE` > `SORT_WITH_ONNXRUNTIME`，都没开就返回空；
- Paddle-Lite 用的是 `.nb` 模型（`paddle_lite_opt` 转换），ONNX 用的是 `.onnx`，两者文件名不同；
- DB 后处理只追踪外轮廓，孔洞单独用洪泛补轮廓，不做 OpenCV 的完整 `RETR_LIST`；
- `ocr_dump` 只认 PPM/PGM，不做 JPEG/PNG 解码。
