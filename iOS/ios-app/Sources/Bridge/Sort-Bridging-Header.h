//
//  Sort-Bridging-Header.h
//
//  ⚠️ 这里只能 #include 契约头文件（纯 C 声明 + extern "C" 守卫）。
//
//  能不能 include 进来，判断标准是「头文件里有没有 C++ 语法」，不是后缀名：
//    ✅ 只含 C 声明的头，哪怕叫 .hpp 也能 include
//    ❌ 含 class / template / namespace / std:: 的头，一律不行
//
//  违反会报：
//    'iostream' file not found
//    failed to emit precompiled header ... for bridging header
//
//  C++ 的可见性靠 .mm 实现，不靠这里。iOS 端不需要 .mm 胶水。
//
#include "engine_ocr_capi.h"
