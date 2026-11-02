#pragma once

#include <cstdint>
#include <vector>

namespace ocr {

/// Otsu 阈值：类间方差最大的灰度值；直方图退化时退回 127。
int otsuThreshold(const std::vector<uint8_t>& gray);

/// 暗视为前景：低于阈值的输出 255，其余 0（THRESH_BINARY_INV）。
std::vector<uint8_t> thresholdInverse(const std::vector<uint8_t>& gray, int threshold);

/// 高于阈值的输出 255，其余 0（THRESH_BINARY）。
std::vector<uint8_t> thresholdBinary(const std::vector<uint8_t>& gray, int threshold);

}  // namespace ocr
