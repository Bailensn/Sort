#pragma once

#include <cstdint>
#include <vector>

#include "Types.hpp"

namespace ocr {

enum class Interp { Nearest, Linear, Cubic };

/// 与 OpenCV 对齐：目标像素中心反算源坐标，越界按边缘复制。
Image8 resize(const Image8& src, int dstWidth, int dstHeight, Interp interp);

Image8 crop(const Image8& src, const Rect& roi);

Image8 copyMakeBorder(const Image8& src, int top, int bottom, int left, int right, uint8_t value);

Image8 warpPerspective(const Image8& src, const float matrix[9], int dstWidth, int dstHeight,
                       Interp interp);

/// BGR 与 RGB 互换，就地改通道顺序。
Image8 swapRedBlue(const Image8& src);

/// Otsu 后统计前景占比，用来判断空白格。
bool hasInk(const Image8& src, double minRatio);

/// 裁到墨迹外框（留 2px 余量）。整格送进 rec 会被缩到 48 高，
/// 字只占一半高度、小数点就剩一两像素；先收紧再缩放能救回这种细节。
Image8 tightenToInk(const Image8& src);

int roundHalfToEven(double value);

}  // namespace ocr
