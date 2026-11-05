cmake_minimum_required(VERSION 3.22.1)

# target 名决定产物名：main -> libmain.so，必须和 Kotlin 侧 System.loadLibrary("main") 一致。
project(main LANGUAGES CXX)

add_library(${CMAKE_PROJECT_NAME} SHARED native-lib.cpp)

target_compile_features(${CMAKE_PROJECT_NAME} PRIVATE cxx_std_23)

set_target_properties(${CMAKE_PROJECT_NAME} PROPERTIES
    CXX_EXTENSIONS OFF
    POSITION_INDEPENDENT_CODE ON)

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    $<$<CONFIG:Debug>:-O0>
    $<$<CONFIG:Debug>:-g>)

# liblog 提供 __android_log_print，libandroid 留给后续可能的平台 API。
find_library(android-lib android)
find_library(log-lib log)

target_link_libraries(${CMAKE_PROJECT_NAME}
    ${android-lib}
    ${log-lib})
