#include "table/Cropper.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

#include "imgproc/Gray.hpp"
#include "imgproc/Scale.hpp"
#include "imgproc/Threshold.hpp"

namespace ocr {
namespace {

constexpr double kAnalysisSide = 600.0;
constexpr double kMinInkRatio = 0.02;
constexpr double kMaxInkRatio = 0.55;
constexpr double kMinKeepRatio = 0.15;
constexpr double kMaxKeepRatio = 0.92;

/// 找占比落在 [kMinInkRatio, kMaxInkRatio] 的最长连续区间；找不到返回空。
bool longestRun(const std::vector<double>& ratio, int& begin, int& end) {
    int bestStart = -1;
    int bestEnd = -1;
    int start = -1;
    for (int i = 0; i < static_cast<int>(ratio.size()); ++i) {
        const bool keep = ratio[static_cast<size_t>(i)] >= kMinInkRatio &&
                          ratio[static_cast<size_t>(i)] <= kMaxInkRatio;
        if (keep) {
            if (start < 0) start = i;
        } else if (start >= 0) {
            if (i - 1 - start > bestEnd - bestStart) {
                bestStart = start;
                bestEnd = i - 1;
            }
            start = -1;
        }
    }
    if (start >= 0 && static_cast<int>(ratio.size()) - 1 - start > bestEnd - bestStart) {
        bestStart = start;
        bestEnd = static_cast<int>(ratio.size()) - 1;
    }
    if (bestStart < 0) return false;
    begin = bestStart;
    end = bestEnd;
    return true;
}

}  // namespace

std::optional<Rect> contentBounds(const Image8& src) {
    if (src.width < 200 || src.height < 200) return std::nullopt;

    const std::vector<uint8_t> gray = toGray(src);
    const double scale =
        std::min(1.0, kAnalysisSide / static_cast<double>(std::max(src.width, src.height)));
    const int smallW = std::max(1, static_cast<int>(std::lround(src.width * scale)));
    const int smallH = std::max(1, static_cast<int>(std::lround(src.height * scale)));
    const std::vector<uint8_t> small = resizeGrayArea(gray, src.width, src.height, smallW, smallH);
    if (small.empty()) return std::nullopt;

    const int threshold = otsuThreshold(small);
    const std::vector<uint8_t> binary = thresholdInverse(small, threshold);

    std::vector<double> columnInk(static_cast<size_t>(smallW), 0.0);
    std::vector<double> rowInk(static_cast<size_t>(smallH), 0.0);
    for (int y = 0; y < smallH; ++y) {
        const size_t row = static_cast<size_t>(y) * static_cast<size_t>(smallW);
        for (int x = 0; x < smallW; ++x) {
            if (binary[row + static_cast<size_t>(x)] == 0) continue;
            columnInk[static_cast<size_t>(x)] += 1.0;
            rowInk[static_cast<size_t>(y)] += 1.0;
        }
    }
    for (int x = 0; x < smallW; ++x) columnInk[static_cast<size_t>(x)] /= smallH;
    for (int y = 0; y < smallH; ++y) rowInk[static_cast<size_t>(y)] /= smallW;

    int columnBegin = 0;
    int columnEnd = 0;
    int rowBegin = 0;
    int rowEnd = 0;
    if (!longestRun(columnInk, columnBegin, columnEnd)) return std::nullopt;
    if (!longestRun(rowInk, rowBegin, rowEnd)) return std::nullopt;

    const double inverse = 1.0 / scale;
    // 留一点余量，免得把贴边的字切掉
    const int padX = std::max(2, static_cast<int>(smallW * 0.01));
    const int padY = std::max(2, static_cast<int>(smallH * 0.01));

    Rect bounds;
    bounds.left = static_cast<int>((std::max(0, columnBegin - padX)) * inverse);
    bounds.top = static_cast<int>((std::max(0, rowBegin - padY)) * inverse);
    bounds.right = static_cast<int>((std::min(smallW, columnEnd + 1 + padX)) * inverse);
    bounds.bottom = static_cast<int>((std::min(smallH, rowEnd + 1 + padY)) * inverse);
    bounds.right = std::min(bounds.right, src.width);
    bounds.bottom = std::min(bounds.bottom, src.height);
    if (bounds.width() <= 0 || bounds.height() <= 0) return std::nullopt;

    const double kept = static_cast<double>(bounds.width()) * bounds.height() /
                        (static_cast<double>(src.width) * src.height);
    if (kept < kMinKeepRatio || kept > kMaxKeepRatio) return std::nullopt;
    return bounds;
}

}  // namespace ocr
