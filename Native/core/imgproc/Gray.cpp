#include "imgproc/Gray.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

namespace ocr {
namespace {

/// 一维滑动平均（水平），边界按边缘复制。
std::vector<uint8_t> boxBlurH(const std::vector<uint8_t>& src, int width, int height, int radius) {
    std::vector<uint8_t> out(src.size(), 0);
    if (radius <= 0) return src;
    for (int y = 0; y < height; ++y) {
        const size_t row = static_cast<size_t>(y) * static_cast<size_t>(width);
        long long sum = 0;
        const int window = radius * 2 + 1;
        for (int x = -radius; x <= radius; ++x) {
            sum += src[row + static_cast<size_t>(std::clamp(x, 0, width - 1))];
        }
        for (int x = 0; x < width; ++x) {
            out[row + static_cast<size_t>(x)] = static_cast<uint8_t>(sum / window);
            const int add = std::clamp(x + radius + 1, 0, width - 1);
            const int drop = std::clamp(x - radius, 0, width - 1);
            sum += src[row + static_cast<size_t>(add)];
            sum -= src[row + static_cast<size_t>(drop)];
        }
    }
    return out;
}

std::vector<uint8_t> boxBlurV(const std::vector<uint8_t>& src, int width, int height, int radius) {
    std::vector<uint8_t> out(src.size(), 0);
    if (radius <= 0) return src;
    for (int x = 0; x < width; ++x) {
        const int window = radius * 2 + 1;
        long long sum = 0;
        for (int y = -radius; y <= radius; ++y) {
            const int row = std::clamp(y, 0, height - 1);
            sum += src[static_cast<size_t>(row) * static_cast<size_t>(width) + static_cast<size_t>(x)];
        }
        for (int y = 0; y < height; ++y) {
            out[static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)] =
                static_cast<uint8_t>(sum / window);
            const int add = std::clamp(y + radius + 1, 0, height - 1);
            const int drop = std::clamp(y - radius, 0, height - 1);
            sum += src[static_cast<size_t>(add) * static_cast<size_t>(width) + static_cast<size_t>(x)];
            sum -= src[static_cast<size_t>(drop) * static_cast<size_t>(width) + static_cast<size_t>(x)];
        }
    }
    return out;
}

}  // namespace

std::vector<uint8_t> toGray(const Image8& src) {
    std::vector<uint8_t> out;
    if (src.width <= 0 || src.height <= 0) return out;
    const size_t pixels = static_cast<size_t>(src.width) * static_cast<size_t>(src.height);
    if (src.data.size() < pixels * 3) return out;

    out.resize(pixels);
    const bool bgr = src.order == ColorOrder::BGR;
    for (size_t i = 0; i < pixels; ++i) {
        const int r = bgr ? src.data[i * 3 + 2] : src.data[i * 3 + 0];
        const int g = src.data[i * 3 + 1];
        const int b = bgr ? src.data[i * 3 + 0] : src.data[i * 3 + 2];
        out[i] = static_cast<uint8_t>((r * 77 + g * 150 + b * 29) >> 8);
    }
    return out;
}

void flattenLighting(std::vector<uint8_t>& gray, int width, int height) {
    if (width <= 0 || height <= 0) return;
    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (gray.size() < pixels) return;

    const double sigma = std::max(std::min(width, height) / 30.0, 8.0);
    // 三次 box blur 逼近高斯，半径由 sigma 反推，够用且是整数运算
    const int radius = std::max(1, static_cast<int>(std::lround(sigma * 0.9)));

    std::vector<uint8_t> background = gray;
    for (int pass = 0; pass < 3; ++pass) {
        background = boxBlurH(background, width, height, radius);
        background = boxBlurV(background, width, height, radius);
    }

    // 背景全黑时除法会除零
    long long sum = 0;
    for (size_t i = 0; i < pixels; ++i) sum += background[i];
    if (sum / static_cast<long long>(pixels) < 1) return;

    for (size_t i = 0; i < pixels; ++i) {
        const int value = background[i];
        if (value <= 0) continue;
        gray[i] = static_cast<uint8_t>(std::min(255, gray[i] * 255 / value));
    }
}

}  // namespace ocr
