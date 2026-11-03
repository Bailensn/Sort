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

/// 一维形态学：erode 要求窗口内全是前景，dilate 只要有一个前景。
/// 前缀和求窗口内前景数，边界按复制处理，和逐点扫描的写法等价。
std::vector<uint8_t> morphLine(const std::vector<uint8_t>& src, int width, int height, int radius,
                               bool horizontal, bool erode) {
    if (radius <= 0) return src;

    std::vector<uint8_t> out(src.size(), 0);
    if (horizontal) {
        std::vector<int> prefix(static_cast<size_t>(width) + 1, 0);
        for (int y = 0; y < height; ++y) {
            const size_t row = static_cast<size_t>(y) * static_cast<size_t>(width);
            for (int x = 0; x < width; ++x) {
                prefix[static_cast<size_t>(x) + 1] =
                    prefix[static_cast<size_t>(x)] + (src[row + static_cast<size_t>(x)] != 0 ? 1 : 0);
            }
            for (int x = 0; x < width; ++x) {
                const int x0 = std::max(0, x - radius);
                const int x1 = std::min(width - 1, x + radius);
                const int count = prefix[static_cast<size_t>(x1) + 1] - prefix[static_cast<size_t>(x0)];
                const int span = x1 - x0 + 1;
                out[row + static_cast<size_t>(x)] =
                    erode ? (count == span ? 255 : 0) : (count > 0 ? 255 : 0);
            }
        }
        return out;
    }

    std::vector<int> prefix(static_cast<size_t>(height) + 1, 0);
    for (int x = 0; x < width; ++x) {
        for (int y = 0; y < height; ++y) {
            prefix[static_cast<size_t>(y) + 1] =
                prefix[static_cast<size_t>(y)] +
                (src[static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)] != 0 ? 1
                                                                                                       : 0);
        }
        for (int y = 0; y < height; ++y) {
            const int y0 = std::max(0, y - radius);
            const int y1 = std::min(height - 1, y + radius);
            const int count = prefix[static_cast<size_t>(y1) + 1] - prefix[static_cast<size_t>(y0)];
            const int span = y1 - y0 + 1;
            out[static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)] =
                erode ? (count == span ? 255 : 0) : (count > 0 ? 255 : 0);
        }
    }
    return out;
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

    // 矩形结构元可分离：先横后竖等于一次二维运算，每像素 O(1)。
    std::vector<uint8_t> out = morphLine(binary, width, height, rx, true, true);
    out = morphLine(out, width, height, ry, false, true);
    out = morphLine(out, width, height, rx, true, false);
    out = morphLine(out, width, height, ry, false, false);
    return out;
}

}  // namespace ocr
