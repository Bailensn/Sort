#pragma once

#include <cstdint>
#include <vector>

namespace ocr {

/// 自适应阈值（mean-C，和 OpenCV ADAPTIVE_THRESH_MEAN_C 一致）：
/// 前景（暗）输出 255，背景 0。blockSize 必须是奇数。
std::vector<uint8_t> adaptiveThresholdMean(const std::vector<uint8_t>& gray, int width, int height,
                                           int blockSize, double c);

/// 形态学开运算：先按 (kw×kh) 腐蚀再膨胀，用来把细线留下来、去掉零碎墨点。
std::vector<uint8_t> morphOpen(const std::vector<uint8_t>& binary, int width, int height, int kw,
                               int kh);

}  // namespace ocr
