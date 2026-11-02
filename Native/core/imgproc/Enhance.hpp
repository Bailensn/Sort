#pragma once

#include <cstdint>
#include <vector>

namespace ocr {

/// CLAHE：8×8 分块直方图均衡，clip 2.0，和 OpenCV createCLAHE(2.0, Size(8,8)) 对齐。
/// 褪色的打印稿、灰底截图靠这一步把线和字拉出来。
void clahe(std::vector<uint8_t>& gray, int width, int height, double clipLimit = 2.0,
           int tiles = 8);

/// 绕画面中心旋转（度，逆时针为正），双线性插值，空出来的边填 255（纸白）。
void rotateGray(std::vector<uint8_t>& gray, int& width, int& height, double degrees);

}  // namespace ocr
