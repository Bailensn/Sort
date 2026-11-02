#include "imgproc/Binary.hpp"

#include <algorithm>
#include <vector>

namespace ocr {
namespace {

/// 积分图，O(1) 求任意窗口和。
std::vector<long long> integralOf(const std::vector<uint8_t>& src, int width, int height) {
    std::vector<long long> sum(static_cast<size_t>(width + 1) * static_cast<size_t>(height + 1), 0);
    for (int y = 0; y < height; ++y) {
        long long rowSum = 0;
        for (int x = 0; x < width; ++x) {
            rowSum += src[static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)];
            sum[static_cast<size_t>(y + 1) * static_cast<size_t>(width + 1) + static_cast<size_t>(x + 1)] =
                sum[static_cast<size_t>(y) * static_cast<size_t>(width + 1) + static_cast<size_t>(x + 1)] + rowSum;
        }
    }
    return sum;
}

}  // namespace

std::vector<uint8_t> adaptiveThresholdMean(const std::vector<uint8_t>& gray, int width, int height,
                                           int blockSize, double c) {
    std::vector<uint8_t> out;
    if (width <= 0 || height <= 0) return out;
    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (gray.size() < pixels) return out;

    int block = blockSize | 1;  // 强制奇数
    const int radius = std::max(1, block / 2);
    const std::vector<long long> sum = integralOf(gray, width, height);
    const size_t stride = static_cast<size_t>(width + 1);

    out.resize(pixels);
    for (int y = 0; y < height; ++y) {
        const int y0 = std::max(0, y - radius);
        const int y1 = std::min(height - 1, y + radius);
        for (int x = 0; x < width; ++x) {
            const int x0 = std::max(0, x - radius);
            const int x1 = std::min(width - 1, x + radius);
            const long long total =
                sum[static_cast<size_t>(y1 + 1) * stride + static_cast<size_t>(x1 + 1)] -
                sum[static_cast<size_t>(y0) * stride + static_cast<size_t>(x1 + 1)] -
                sum[static_cast<size_t>(y1 + 1) * stride + static_cast<size_t>(x0)] +
                sum[static_cast<size_t>(y0) * stride + static_cast<size_t>(x0)];
            const int count = (x1 - x0 + 1) * (y1 - y0 + 1);
            const double mean = static_cast<double>(total) / count;
            const size_t index = static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x);
            out[index] = gray[index] < mean - c ? 255 : 0;
        }
    }
    return out;
}

std::vector<uint8_t> morphOpen(const std::vector<uint8_t>& binary, int width, int height, int kw,
                               int kh) {
    if (width <= 0 || height <= 0) return {};
    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (binary.size() < pixels) return {};

    const int rx = std::max(0, kw / 2);
    const int ry = std::max(0, kh / 2);

    // 腐蚀：窗口内全是前景才保留
    std::vector<uint8_t> eroded(pixels, 0);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            bool all = true;
            for (int dy = -ry; dy <= ry && all; ++dy) {
                for (int dx = -rx; dx <= rx; ++dx) {
                    const int sx = std::clamp(x + dx, 0, width - 1);
                    const int sy = std::clamp(y + dy, 0, height - 1);
                    if (binary[static_cast<size_t>(sy) * static_cast<size_t>(width) + static_cast<size_t>(sx)] == 0) {
                        all = false;
                        break;
                    }
                }
            }
            eroded[static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)] = all ? 255 : 0;
        }
    }

    // 膨胀：窗口内有一个前景就算前景
    std::vector<uint8_t> dilated(pixels, 0);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            bool any = false;
            for (int dy = -ry; dy <= ry && !any; ++dy) {
                for (int dx = -rx; dx <= rx; ++dx) {
                    const int sx = std::clamp(x + dx, 0, width - 1);
                    const int sy = std::clamp(y + dy, 0, height - 1);
                    if (eroded[static_cast<size_t>(sy) * static_cast<size_t>(width) + static_cast<size_t>(sx)] != 0) {
                        any = true;
                        break;
                    }
                }
            }
            dilated[static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)] = any ? 255 : 0;
        }
    }
    return dilated;
}

}  // namespace ocr
