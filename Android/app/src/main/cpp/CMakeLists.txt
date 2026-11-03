cmake_minimum_required(VERSION 3.22.0)
project(main)

# 核心全在仓库根的 Native/；OCR 单独编成 libocr.so，libmain.so 只做 JNI 胶水并链接它。
set(SHARED_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../Native)
set(SORT_OCR_SHARED ON CACHE BOOL "" FORCE)
# 安卓走 MNN：libMNN.so 只有 2.4 MB，ORT 那份 .so 是 32 MB。
set(SORT_WITH_MNN ON CACHE BOOL "" FORCE)
add_subdirectory(${SHARED_ROOT} native_build)

# 产物 libmain.so，三端同名
add_library(main SHARED jni_engine.cpp)
target_link_libraries(main ocr log)
