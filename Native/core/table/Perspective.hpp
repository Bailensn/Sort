#pragma once

#include "ocr/Types.hpp"

namespace ocr {

/// 把四条边围成的四边形（左上、右上、右下、左下）拉正成矩形。
/// 输出尺寸取对边的最大值；四点在原图像素坐标，越界部分按纸白填。
Image8 rectifyPerspective(const Image8& src, const PointF quad[4]);

}  // namespace ocr
