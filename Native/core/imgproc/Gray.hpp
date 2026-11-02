#pragma once

#include <cstdint>
#include <vector>

#include "ocr/Types.hpp"

namespace ocr {

/// RGB 三通道 → 单通道灰度（ITU-R BT.601 权重，和 OpenCV 的 RGBA2GRAY 一致）。
std::vector<uint8_t> toGray(const Image8& src);

/// 光照归一化：除以低频背景，压平阴影和打光不均。
/// sigma 取短边的 1/30（至少 8），和高斯核半径的换算在实现里。
void flattenLighting(std::vector<uint8_t>& gray, int width, int height);

}  // namespace ocr
