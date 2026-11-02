#include "table/EdgeLines.hpp"

#include <algorithm>
#include <cstdint>
#include <vector>

namespace ocr {
namespace {

/// 边缘留白超过画面这个比例、且里面确实有墨迹，才补线。
constexpr float kEdgeRatio = 0.25f;
/// 一条横带里暗像素占比到多少算「有内容」。
constexpr float kInkRatio = 0.02f;

int luminanceAt(const Image8& src, int x, int y) {
    const uint8_t* pixel = src.row(y) + static_cast<size_t>(x) * 3u;
    const bool bgr = src.order == ColorOrder::BGR;
    const int r = bgr ? pixel[2] : pixel[0];
    const int g = pixel[1];
    const int b = bgr ? pixel[0] : pixel[2];
    return (r * 299 + g * 587 + b * 114) / 1000;
}

bool hasInkRow(const Image8& src, int fromY, int toY) {
    const int step = std::max(1, src.width / 160);
    for (int y = std::max(0, fromY); y < std::min(toY, src.height); y += 2) {
        int dark = 0;
        int total = 0;
        for (int x = 0; x < src.width; x += step) {
            if (luminanceAt(src, x, y) < 160) ++dark;
            ++total;
        }
        if (total > 0 && static_cast<float>(dark) / static_cast<float>(total) >= kInkRatio) return true;
    }
    return false;
}

bool hasInkColumn(const Image8& src, int fromX, int toX) {
    const int step = std::max(1, src.height / 160);
    for (int x = std::max(0, fromX); x < std::min(toX, src.width); x += 2) {
        int dark = 0;
        int total = 0;
        for (int y = 0; y < src.height; y += step) {
            if (luminanceAt(src, x, y) < 160) ++dark;
            ++total;
        }
        if (total > 0 && static_cast<float>(dark) / static_cast<float>(total) >= kInkRatio) return true;
    }
    return false;
}

}  // namespace

LineDetection extendEdgeLines(const Image8& src, const LineDetection& detection) {
    if (!detection.usable() || src.empty()) return detection;

    LineDetection out = detection;
    const float width = static_cast<float>(detection.width);
    const float height = static_cast<float>(detection.height);
    const float midX = width / 2.0f;
    const float midY = height / 2.0f;

    float topLine = 0.0f;
    float bottomLine = 0.0f;
    float leftLine = 0.0f;
    float rightLine = 0.0f;
    {
        bool first = true;
        for (const auto& line : detection.horizontal) {
            const float value = line.valueAt(midX);
            if (first) {
                topLine = bottomLine = value;
                first = false;
            } else {
                topLine = std::min(topLine, value);
                bottomLine = std::max(bottomLine, value);
            }
        }
        first = true;
        for (const auto& line : detection.vertical) {
            const float value = line.valueAt(midY);
            if (first) {
                leftLine = rightLine = value;
                first = false;
            } else {
                leftLine = std::min(leftLine, value);
                rightLine = std::max(rightLine, value);
            }
        }
    }

    if (topLine > height * 0.01f && topLine < height * kEdgeRatio &&
        hasInkRow(src, 0, std::max(1, static_cast<int>(topLine)))) {
        out.horizontal.push_back(FittedLine{0.0f, 0.0f});
    }
    if (bottomLine < height * 0.99f && bottomLine > height * (1.0f - kEdgeRatio) &&
        hasInkRow(src, std::min(src.height - 1, static_cast<int>(bottomLine)), src.height)) {
        out.horizontal.push_back(FittedLine{0.0f, height});
    }
    if (leftLine > width * 0.01f && leftLine < width * kEdgeRatio &&
        hasInkColumn(src, 0, std::max(1, static_cast<int>(leftLine)))) {
        out.vertical.push_back(FittedLine{0.0f, 0.0f});
    }
    if (rightLine < width * 0.99f && rightLine > width * (1.0f - kEdgeRatio) &&
        hasInkColumn(src, std::min(src.width - 1, static_cast<int>(rightLine)), src.width)) {
        out.vertical.push_back(FittedLine{0.0f, width});
    }
    return out;
}

}  // namespace ocr
