#pragma once

#include "ocr/Types.hpp"
#include "table/LineDetector.hpp"

namespace ocr {

/// 把检测到的表格线涂成纸白，交给识别当底图。
/// 线头残留会被认成「1」「|」，空白格就是这么被填上值的。
Image8 eraseLines(const Image8& src, const LineDetection& detection, int halfWidth);

}  // namespace ocr
