cmake_minimum_required(VERSION 3.5.0)
project(main)

# 共享层在仓库根的 Native/，这里只留 NAPI 胶水
set(NATIVERENDER_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR})
set(SHARED_ROOT ${NATIVERENDER_ROOT_PATH}/../../../../../Native)

include_directories(${NATIVERENDER_ROOT_PATH} ${SHARED_ROOT}/capi)

add_definitions("-DLOG_DOMAIN=0x0000")
add_definitions("-DLOG_TAG=\"engine\"")

# 共享层（core + cabi）—— 和 Android 那份 CMakeLists 编的是同一批源文件
add_library(engine_core STATIC
        ${SHARED_ROOT}/core/Engine.cpp
        ${SHARED_ROOT}/capi/engine_capi.cpp)
target_compile_options(engine_core PRIVATE -std=c++17)

# 三端产物统一叫 libmain.so，ArkTS 的 import 和 types/libmain 都不用动
add_library(main SHARED napi_init.cpp)
target_link_libraries(main PUBLIC engine_core libace_napi.z.so libhilog_ndk.z.so)
