#pragma once

#include <vector>

#include "ocr/Types.hpp"
#include "table/LineDetector.hpp"

namespace ocr {

/// 把拟合出的表格线叠到图上，方便和原图直接对照着看对齐情况。
Image8 drawGridOverlay(const Image8& src, const LineDetection& detection);

/// 把检测框描到图上：一眼看出框是贴着一格，还是把一片格子连成了大块。
Image8 drawBoxesOverlay(const Image8& src, const std::vector<Box>& boxes);

}  // namespace ocr
