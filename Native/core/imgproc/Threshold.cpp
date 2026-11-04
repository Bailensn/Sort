#include "imgproc/Threshold.hpp"

#include <algorithm>
#include <array>
#include <cstdint>

namespace ocr {
namespace {

std::array<long long, 256> histogramOf(const std::vector<uint8_t>& gray) {
    std::array<long long, 256> histogram{};
    for (uint8_t value : gray) ++histogram[value];
    return histogram;
}

}  // namespace

int otsuThreshold(const std::vector<uint8_t>& gray) {
    if (gray.empty()) return 127;
    const std::array<long long, 256> histogram = histogramOf(gray);

    const long long total = static_cast<long long>(gray.size());
    long long weightedSum = 0;
    for (int i = 0; i < 256; ++i) weightedSum += static_cast<long long>(i) * histogram[i];

    long long backgroundWeight = 0;
    long long backgroundSum = 0;
    double bestVariance = -1.0;
    int bestThreshold = 127;
    for (int i = 0; i < 256; ++i) {
        backgroundWeight += histogram[i];
        if (backgroundWeight == 0) continue;
        const long long foregroundWeight = total - backgroundWeight;
        if (foregroundWeight == 0) break;

        backgroundSum += static_cast<long long>(i) * histogram[i];
        const double meanBack = static_cast<double>(backgroundSum) / static_cast<double>(backgroundWeight);
        const double meanFore =
            static_cast<double>(weightedSum - backgroundSum) / static_cast<double>(foregroundWeight);
        const double delta = meanBack - meanFore;
        const double variance = static_cast<double>(backgroundWeight) *
                                static_cast<double>(foregroundWeight) * delta * delta;
        if (variance > bestVariance) {
            bestVariance = variance;
            bestThreshold = i;
        }
    }
    return bestThreshold;
}

std::vector<uint8_t> thresholdInverse(const std::vector<uint8_t>& gray, int threshold) {
    std::vector<uint8_t> out(gray.size(), 0);
    for (size_t i = 0; i < gray.size(); ++i) {
        out[i] = gray[i] <= threshold ? 255 : 0;
    }
    return out;
}

std::vector<uint8_t> binarizeForText(const std::vector<uint8_t>& gray) {
    return thresholdBinary(gray, otsuThreshold(gray));
}

std::vector<uint8_t> binarizeSoft(const std::vector<uint8_t>& gray, int width, int height) {
    if (width <= 0 || height <= 0) return {};
    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (gray.size() < pixels) return {};

    // 窗口取格子量级：按块压背景，纸纹和阴影就不会被切成整片黑块
    const int radius = std::clamp(std::min(width, height) / 48, 8, 40);
    const size_t stride = static_cast<size_t>(width) + 1;
    std::vector<long long> integral((static_cast<size_t>(height) + 1) * stride, 0);
    for (int y = 1; y <= height; ++y) {
        long long rowSum = 0;
        for (int x = 1; x <= width; ++x) {
            rowSum += gray[static_cast<size_t>(y - 1) * static_cast<size_t>(width) +
                           static_cast<size_t>(x - 1)];
            integral[static_cast<size_t>(y) * stride + static_cast<size_t>(x)] =
                integral[static_cast<size_t>(y - 1) * stride + static_cast<size_t>(x)] + rowSum;
        }
    }

    std::vector<uint8_t> out(pixels, 255);
    for (int y = 0; y < height; ++y) {
        const int top = std::max(y - radius, 0);
        const int bottom = std::min(y + radius + 1, height);
        for (int x = 0; x < width; ++x) {
            const int left = std::max(x - radius, 0);
            const int right = std::min(x + radius + 1, width);
            const long long sum =
                integral[static_cast<size_t>(bottom) * stride + static_cast<size_t>(right)] -
                integral[static_cast<size_t>(top) * stride + static_cast<size_t>(right)] -
                integral[static_cast<size_t>(bottom) * stride + static_cast<size_t>(left)] +
                integral[static_cast<size_t>(top) * stride + static_cast<size_t>(left)];
            const int count = (bottom - top) * (right - left);
            const int mean = static_cast<int>(sum / std::max(count, 1));
            const int threshold = std::max(1, mean * 9 / 10);
            const size_t index = static_cast<size_t>(y) * static_cast<size_t>(width) +
                                 static_cast<size_t>(x);
            out[index] = gray[index] < threshold ? gray[index] : 255;
        }
    }
    return out;
}

std::vector<uint8_t> thresholdBinary(const std::vector<uint8_t>& gray, int threshold) {
    std::vector<uint8_t> out(gray.size(), 0);
    for (size_t i = 0; i < gray.size(); ++i) {
        out[i] = gray[i] > threshold ? 255 : 0;
    }
    return out;
}

}  // namespace ocr
