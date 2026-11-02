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

# 主机上拿 Linux 版 libmindspore-lite.so 跑真实的 .ms 模型（CI 用），
# 逻辑和鸿蒙那份 MindSporeBackend 完全同一份源码。
option(SORT_MSLITE_HOST_TEST "Run OCR on host against the Linux MindSpore Lite runtime" OFF)
if (SORT_MSLITE_HOST_TEST)
    set(SORT_WITH_MINDSORE_LITE ON CACHE BOOL "" FORCE)
endif ()

# OCR 核心：零平台依赖，只吃 Image8 和张量；ORT 后端在 adapters/ 里，按需打开。
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
            core/table/ColumnCleaner.cpp
            core/table/GridBuilder.cpp
            core/table/TableLogic.cpp
            capi/engine_ocr_capi.cpp
            adapters/BackendFactory.cpp
            adapters/onnx/OnnxBackend.cpp
            adapters/paddle_lite/PaddleLiteBackend.cpp
            adapters/mindspore/MindSporeBackend.cpp)

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
        target_link_libraries(${OCR_TARGET} onnxruntime)
    endif ()

    if (SORT_WITH_ONNXRUNTIME)
        target_compile_definitions(${OCR_TARGET} PUBLIC SORT_WITH_ONNXRUNTIME=1)
    endif ()

    # MindSpore Lite：模型是 .ms；鸿蒙官方支持，Android/iOS 也能用。
    option(SORT_WITH_MINDSORE_LITE "Link against MindSpore Lite" OFF)
    if (SORT_WITH_MINDSORE_LITE)
        target_compile_definitions(${OCR_TARGET} PUBLIC SORT_WITH_MINDSORE_LITE=1)
        # adapters/mindspore/ohos 里是华为 OH_AI C API 头（和真机 sysroot 一致），
        # 不用依赖 NDK 装没装头文件
        target_include_directories(${OCR_TARGET} PRIVATE
                ${CMAKE_CURRENT_SOURCE_DIR}/adapters/mindspore/ohos)
    endif ()

    if (SORT_MSLITE_HOST_TEST)
        if (NOT MSLITE_ROOT)
            message(FATAL_ERROR "SORT_MSLITE_HOST_TEST 要配 -DMSLITE_ROOT=<mindspore-lite-*-linux-x64>")
        endif ()
        # OH_AI 垫片把调用转给上游的 MS_*，于是同一份适配层能在 CI 上跑
        add_executable(ocr_mslite_dump tools/ocr_dump/main.cpp tests/mslite_shim/oh_ai_shim.cpp)
        # ohos 头是 PRIVATE 挂在 ocr_core 上的，垫片自己也要看得见
        target_include_directories(ocr_mslite_dump PRIVATE
                ${CMAKE_CURRENT_SOURCE_DIR}/adapters/mindspore/ohos ${MSLITE_ROOT}/runtime)
        # libmindspore-lite.so 依赖 glog，包里只给了 converter/lib 那份
        file(GLOB MSLITE_GLOG "${MSLITE_ROOT}/tools/converter/lib/libmindspore_glog.so*")
        target_link_libraries(ocr_mslite_dump PRIVATE
                ${OCR_TARGET} ${MSLITE_ROOT}/runtime/lib/libmindspore-lite.so ${MSLITE_GLOG})
    endif ()

    # Paddle-Lite：模型是 .nb；三端各有自己的库，用法和 ORT 那套一样。
    option(SORT_WITH_PADDLE_LITE "Link against Paddle-Lite" OFF)
    if (SORT_WITH_PADDLE_LITE)
        target_compile_definitions(${OCR_TARGET} PUBLIC SORT_WITH_PADDLE_LITE=1)
    endif ()
endif ()
