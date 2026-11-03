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

# OCR 核心：零平台依赖，只吃 Image8 和张量；推理后端在 adapters/ 里，按需打开。
option(SORT_WITH_OCR "Build the OCR core" ON)
if (SORT_WITH_OCR)
    set(OCR_SOURCES
            core/ocr/Charset.cpp
            core/ocr/DbPostprocess.cpp
            core/ocr/Geometry.cpp
            core/ocr/ImageOps.cpp
            core/ocr/OcrEngine.cpp
            core/ocr/Postprocess.cpp
            core/ocr/Preprocess.cpp
            core/ocr/QuadCrop.cpp
            core/imgproc/Binary.cpp
            core/imgproc/Enhance.cpp
            core/imgproc/Gray.cpp
            core/imgproc/Scale.cpp
            core/imgproc/Threshold.cpp
            core/imgproc/Warp.cpp
            core/table/ColumnCleaner.cpp
            core/table/Cropper.cpp
            core/table/EdgeLines.cpp
            core/table/GridBuilder.cpp
            core/table/LineDetector.cpp
            core/table/LineErase.cpp
            core/table/LineWidth.cpp
            core/table/Overlay.cpp
            core/table/Perspective.cpp
            core/table/QuadFit.cpp
            core/table/Skew.cpp
            core/table/TableAnalyzer.cpp
            core/table/TableLogic.cpp
            capi/engine_ocr_capi.cpp
            adapters/BackendFactory.cpp
            adapters/onnx/OnnxBackend.cpp
            adapters/paddle_lite/PaddleLiteBackend.cpp
            adapters/mnn/MnnBackend.cpp)

    # Android 要一份独立的 libocr.so；iOS 和本地测试直接静态编进 App / 可执行文件。
    option(SORT_OCR_SHARED "Build OCR as a shared library" OFF)
    if (SORT_OCR_SHARED)
        add_library(ocr SHARED ${OCR_SOURCES})
        set(OCR_TARGET ocr)
    else ()
        add_library(ocr_core STATIC ${OCR_SOURCES})
        set(OCR_TARGET ocr_core)
    endif ()

    target_include_directories(${OCR_TARGET} PUBLIC core core/ocr adapters adapters/onnx capi)

    # 整图回读和逐格识别并行跑，需要线程支持
    find_package(Threads REQUIRED)
    target_link_libraries(${OCR_TARGET} PUBLIC Threads::Threads)

    option(SORT_BUILD_TESTS "Build native smoke tests" ON)
    if (SORT_BUILD_TESTS)
        enable_testing()
        add_executable(ocr_smoke tests/ocr_smoke.cpp)
        target_link_libraries(ocr_smoke ${OCR_TARGET})
        add_test(NAME ocr_smoke COMMAND ocr_smoke)

        # 表格后处理的共享实现，用例和安卓的 TableSelfCheckTest 一一对应
        add_executable(table_logic_test tests/table_logic_test.cpp)
        target_link_libraries(table_logic_test ${OCR_TARGET})
        add_test(NAME table_logic_test COMMAND table_logic_test)

        # 合成图上的几何回归：裁切、纠偏、找线、外框、叠线，不需要模型
        add_executable(table_image_test tests/table_image_test.cpp)
        target_link_libraries(table_image_test ${OCR_TARGET})
        add_test(NAME table_image_test COMMAND table_image_test)
    endif ()

    # 对拍用的命令行：同一张图两端各跑一次，diff JSON。
    option(SORT_BUILD_TOOLS "Build CLI tools" ON)
    if (SORT_BUILD_TOOLS)
        add_executable(ocr_dump tools/ocr_dump/main.cpp)
        target_link_libraries(ocr_dump ${OCR_TARGET})
    endif ()

    option(SORT_WITH_ONNXRUNTIME "Link against ONNX Runtime" OFF)

    # Android：Gradle 的 prepareOrt 把 AAR 里的头文件和 libonnxruntime.so 解到 ORT_ROOT。
    if (ORT_ROOT)
        set(SORT_WITH_ONNXRUNTIME ON)
        target_include_directories(${OCR_TARGET} PRIVATE ${ORT_ROOT}/headers)
        add_library(onnxruntime SHARED IMPORTED)
        set_target_properties(onnxruntime PROPERTIES
                IMPORTED_LOCATION ${ORT_ROOT}/jni/${ANDROID_ABI}/libonnxruntime.so)
        target_link_libraries(${OCR_TARGET} PUBLIC onnxruntime)
    endif ()

    if (SORT_WITH_ONNXRUNTIME)
        target_compile_definitions(${OCR_TARGET} PUBLIC SORT_WITH_ONNXRUNTIME=1)
    endif ()


    # MNN：三端共用一套 .mnn 模型。库用官方预编译包（Android 2.4 MB / 鸿蒙 2 MB），
    # 头文件是仓库里 vendored 的 adapters/mnn/include。
    option(SORT_WITH_MNN "Link against MNN" OFF)
    if (SORT_WITH_MNN)
        target_compile_definitions(${OCR_TARGET} PUBLIC SORT_WITH_MNN=1)
        target_include_directories(${OCR_TARGET} PUBLIC adapters/mnn/include)
        set(mnn_lib "${MNN_LIB}")
        if (NOT mnn_lib AND DEFINED ENV{MNN_LIB})
            set(mnn_lib "$ENV{MNN_LIB}")
        endif ()
        if (mnn_lib)
            target_link_libraries(${OCR_TARGET} PUBLIC ${mnn_lib})
        endif ()
    endif ()

    # Paddle-Lite：模型是 .nb；三端各有自己的库，用法和 ORT 那套一样。
    option(SORT_WITH_PADDLE_LITE "Link against Paddle-Lite" OFF)
    if (SORT_WITH_PADDLE_LITE)
        target_compile_definitions(${OCR_TARGET} PUBLIC SORT_WITH_PADDLE_LITE=1)
    endif ()
endif ()
