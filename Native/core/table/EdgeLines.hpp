#pragma once

#include "ocr/Types.hpp"
#include "table/LineDetector.hpp"

namespace ocr {

/// 首/末行被画面边缘截断时补一条贴边的虚拟线，否则整行会被当成表格外面丢掉。
LineDetection extendEdgeLines(const Image8& src, const LineDetection& detection);

}  // namespace ocr
