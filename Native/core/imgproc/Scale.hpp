#pragma once

#include <cstdint>
#include <vector>

namespace ocr {

/// 灰度图按面积平均缩放（相当于 INTER_AREA）；只用于分析，不用来出图。
std::vector<uint8_t> resizeGrayArea(const std::vector<uint8_t>& src, int width, int height,
                                    int dstWidth, int dstHeight);

}  // namespace ocr
