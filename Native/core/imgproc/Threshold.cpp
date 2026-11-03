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

std::vector<uint8_t> thresholdBinary(const std::vector<uint8_t>& gray, int threshold) {
    std::vector<uint8_t> out(gray.size(), 0);
    for (size_t i = 0; i < gray.size(); ++i) {
        out[i] = gray[i] > threshold ? 255 : 0;
    }
    return out;
}

}  // namespace ocr
