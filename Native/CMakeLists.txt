cmake_minimum_required(VERSION 3.18)
project(NativeCore LANGUAGES C CXX)

# 顶层 CMakeLists 的用途：
#   1. 本地快速验证 OCR 核心 + C ABI 能编过（不依赖任何平台 SDK）
#   2. 各端的 CMakeLists 各自引用这里，胶水不放在 Native/ 里
#
# 这个工程不产出 App：iOS 走 XcodeGen，Android 走 Gradle+CMake。

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# OCR 核心：零平台依赖，只吃 Image8 和张量；ORT 后端在 adapters/ 里，按需打开。
option(SORT_WITH_OCR "Build the OCR core" ON)
if (SORT_WITH_OCR)
    add_library(ocr_core STATIC
            core/ocr/Charset.cpp
            core/ocr/DbPostprocess.cpp
            core/ocr/Geometry.cpp
            core/ocr/ImageOps.cpp
            core/ocr/OcrEngine.cpp
            core/ocr/Postprocess.cpp
            core/ocr/Preprocess.cpp
            core/ocr/QuadCrop.cpp
            capi/engine_ocr_capi.cpp
            adapters/onnx/OnnxBackend.cpp)
    target_include_directories(ocr_core PUBLIC core core/ocr adapters/onnx capi)

    option(SORT_BUILD_TESTS "Build native smoke tests" ON)
    if (SORT_BUILD_TESTS)
        enable_testing()
        add_executable(ocr_smoke tests/ocr_smoke.cpp)
        target_link_libraries(ocr_smoke ocr_core)
        add_test(NAME ocr_smoke COMMAND ocr_smoke)
    endif ()

    # 对拍用的命令行：同一张图两端各跑一次，diff JSON。
    option(SORT_BUILD_TOOLS "Build CLI tools" ON)
    if (SORT_BUILD_TOOLS)
        add_executable(ocr_dump tools/ocr_dump/main.cpp)
        target_link_libraries(ocr_dump ocr_core)
    endif ()

    option(SORT_WITH_ONNXRUNTIME "Link against ONNX Runtime" OFF)
    if (SORT_WITH_ONNXRUNTIME)
        target_compile_definitions(ocr_core PUBLIC SORT_WITH_ONNXRUNTIME=1)
        # 各端自己把 onnxruntime 的 include/lib 加进来，这里只管编译开关。
    endif ()
endif ()
