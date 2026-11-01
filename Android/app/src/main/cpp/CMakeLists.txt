cmake_minimum_required(VERSION 3.22.0)
project(main)

# 核心全在仓库根的 Native/，这里只编 JNI 胶水；add_subdirectory 把 engine_core / ocr_core 带进来。
set(SHARED_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../Native)
add_subdirectory(${SHARED_ROOT} native_build)

# 产物 libmain.so，三端同名
add_library(main SHARED jni_engine.cpp)
target_link_libraries(main ocr_core log)
