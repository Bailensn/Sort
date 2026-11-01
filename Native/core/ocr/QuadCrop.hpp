#pragma once

#include "Types.hpp"

namespace ocr {

/// 按四点做透视裁图，比例过于竖长的再转 90°。
Image8 quadCrop(const Image8& src, const Box& box);

/// 四点透视变换矩阵，src 与 dst 各四个点（行优先 3x3）。
bool perspectiveTransform(const PointF src[4], const PointF dst[4], float matrix[9]);

}  // namespace ocr
