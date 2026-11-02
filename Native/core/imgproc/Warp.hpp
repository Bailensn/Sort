#pragma once

#include <cstdint>
#include <vector>

#include "ocr/Types.hpp"

namespace ocr {

/// 按矩形裁一块；越界部分按纸白填，不返回空。
Image8 cropImage(const Image8& src, const Rect& rect);

/// 单通道灰度套成三通道：识别后端只吃三通道。
Image8 grayToImage(const std::vector<uint8_t>& gray, int width, int height);

/// 绕画面中心旋转（度，逆时针为正），双线性插值，空出来的边填纸白。
Image8 rotateImage(const Image8& src, double degrees);

}  // namespace ocr
