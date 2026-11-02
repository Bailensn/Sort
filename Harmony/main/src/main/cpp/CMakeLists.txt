cmake_minimum_required(VERSION 3.22.0)
project(main)

# 核心在仓库根的 Native/，这里只编 NAPI 胶水。
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(NATIVERENDER_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR})
set(SHARED_ROOT ${NATIVERENDER_ROOT_PATH}/../../../../../Native)

# MindSpore Lite 在鸿蒙上是系统 Kit：运行时由系统提供，链接时借 SDK sysroot 里那份。
# 头文件用仓库里 vendored 的 OH_AI 头（Native/adapters/mindspore/ohos），不依赖 NDK 带没带。
if (DEFINED ENV{MSLITE_LIB})
    set(SORT_WITH_MINDSORE_LITE ON CACHE BOOL "" FORCE)
endif ()

# 只编 OCR 核心，测试和命令行工具留给本地/CI 的 host 构建。
set(SORT_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SORT_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
add_subdirectory(${SHARED_ROOT} native_build)

add_definitions("-DLOG_DOMAIN=0x0000")
add_definitions("-DLOG_TAG=\"engine\"")

# 三端产物统一叫 libmain.so，ArkTS 的 import 和 types/libmain 都不用动
add_library(main SHARED napi_init.cpp)
target_link_libraries(main PUBLIC ocr_core libace_napi.z.so libhilog_ndk.z.so)

if (DEFINED ENV{MSLITE_LIB})
    target_link_libraries(main PUBLIC $ENV{MSLITE_LIB})
endif ()
