#pragma once

#include <string>
#include <vector>

#include "Types.hpp"

namespace ocr {

/// 把 minAreaRect 的四个角排成左上、右上、右下、左下。
Box orderQuad(const PointF points[4]);

/// 按 unclip_ratio 向外扩框，圆角连接，与 Android 的 PolygonUnclip 同法。
std::vector<PointF> unclip(const std::vector<PointF>& points, double unclipRatio);

/// CTC 贪心解码：逐帧 argmax、去重、跳 blank，字符索引要减一。
std::vector<std::pair<std::string, float>> ctcDecode(const Tensor& output,
                                                     const std::vector<std::string>& charset);

/// 去掉格子两端的线头噪声、汉字之间的空格。
std::string cleanCellText(const std::string& raw);

bool looksLikeLine(const std::string& raw);

/// 阅读序：先按 y 再按 x，同一行内按 x 修正。
void sortInReadingOrder(std::vector<Box>& boxes);

}  // namespace ocr
