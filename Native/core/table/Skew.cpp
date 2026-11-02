#include "table/Skew.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

#include "imgproc/Gray.hpp"
#include "imgproc/Scale.hpp"
#include "imgproc/Threshold.hpp"
#include "imgproc/Warp.hpp"

namespace ocr {
namespace {

constexpr double kMaxAnalysisWidth = 800.0;
constexpr double kMaxAngle = 3.0;
constexpr double kAngleStep = 0.25;
constexpr double kMinGain = 1.02;

/// 二值图按最近邻旋转，露出来的边填 0（也就是「这里没有墨」）。
std::vector<uint8_t> rotateBinary(const std::vector<uint8_t>& src, int width, int height,
                                  double degrees, int& outWidth, int& outHeight) {
    outWidth = width;
    outHeight = height;
    if (std::abs(degrees) < 1e-9) return src;
    const double rad = degrees * M_PI / 180.0;
    const double c = std::abs(std::cos(rad));
    const double s = std::abs(std::sin(rad));
    const int newW = static_cast<int>(std::lround(width * c + height * s));
    const int newH = static_cast<int>(std::lround(width * s + height * c));
    if (newW <= 0 || newH <= 0) return src;
    outWidth = newW;
    outHeight = newH;

    std::vector<uint8_t> out(static_cast<size_t>(newW) * static_cast<size_t>(newH), 0);
    const double cx = width / 2.0;
    const double cy = height / 2.0;
    const double ncx = newW / 2.0;
    const double ncy = newH / 2.0;
    const double cosA = std::cos(-rad);
    const double sinA = std::sin(-rad);

    for (int y = 0; y < newH; ++y) {
        for (int x = 0; x < newW; ++x) {
            const double dx = x - ncx;
            const double dy = y - ncy;
            const int sx = static_cast<int>(std::lround(cosA * dx - sinA * dy + cx));
            const int sy = static_cast<int>(std::lround(sinA * dx + cosA * dy + cy));
            if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue;
            out[static_cast<size_t>(y) * static_cast<size_t>(newW) + static_cast<size_t>(x)] =
                src[static_cast<size_t>(sy) * static_cast<size_t>(width) + static_cast<size_t>(sx)];
        }
    }
    return out;
}

/// 行投影的锐度：文字行/表格线越对齐，相邻行和的差越大。
double rowPeakScore(const std::vector<uint8_t>& binary, int width, int height) {
    std::vector<long long> sums(static_cast<size_t>(height), 0);
    for (int y = 0; y < height; ++y) {
        const size_t row = static_cast<size_t>(y) * static_cast<size_t>(width);
        long long ink = 0;
        for (int x = 0; x < width; ++x) {
            if (binary[row + static_cast<size_t>(x)] != 0) ++ink;
        }
        sums[static_cast<size_t>(y)] = ink;
    }
    double score = 0.0;
    for (size_t i = 1; i < sums.size(); ++i) {
        const double delta = static_cast<double>(sums[i] - sums[i - 1]);
        score += delta * delta;
    }
    return score;
}

double estimateAngle(const Image8& src) {
    const std::vector<uint8_t> gray = toGray(src);
    if (gray.empty()) return 0.0;

    int width = src.width;
    int height = src.height;
    std::vector<uint8_t> work = gray;
    const double scale = std::min(1.0, kMaxAnalysisWidth / static_cast<double>(src.width));
    if (scale < 1.0) {
        const int smallW = std::max(1, static_cast<int>(std::lround(src.width * scale)));
        const int smallH = std::max(1, static_cast<int>(std::lround(src.height * scale)));
        const std::vector<uint8_t> small = resizeGrayArea(gray, src.width, src.height, smallW, smallH);
        if (!small.empty()) {
            work = small;
            width = smallW;
            height = smallH;
        }
    }

    const std::vector<uint8_t> binary = thresholdInverse(work, otsuThreshold(work));
    if (binary.empty()) return 0.0;

    double bestAngle = 0.0;
    double bestScore = -1.0;
    double zeroScore = 0.0;
    for (double angle = -kMaxAngle; angle <= kMaxAngle + 1e-9; angle += kAngleStep) {
        int rotatedW = width;
        int rotatedH = height;
        const std::vector<uint8_t> rotated = rotateBinary(binary, width, height, angle, rotatedW, rotatedH);
        const double score = rowPeakScore(rotated, rotatedW, rotatedH);
        if (std::abs(angle) < 1e-6) zeroScore = score;
        if (score > bestScore) {
            bestScore = score;
            bestAngle = angle;
        }
    }

    if (zeroScore > 0.0 && bestScore < zeroScore * kMinGain) return 0.0;
    return bestAngle;
}

}  // namespace

SkewResult deskewImage(const Image8& src) {
    SkewResult result;
    result.image = src;
    if (src.empty()) return result;

    const double angle = estimateAngle(src);
    if (std::abs(angle) < 0.2) return result;

    Image8 rotated = rotateImage(src, angle);
    if (rotated.empty()) return result;
    result.image = std::move(rotated);
    result.degrees = angle;
    return result;
}

}  // namespace ocr
