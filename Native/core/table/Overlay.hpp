#pragma once

#include "ocr/Types.hpp"
#include "table/LineDetector.hpp"

namespace ocr {

/// 把拟合出的表格线叠到图上，方便和原图直接对照着看对齐情况。
Image8 drawGridOverlay(const Image8& src, const LineDetection& detection);

}  // namespace ocr
