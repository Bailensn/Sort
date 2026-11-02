#include "table/QuadFit.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

#include "imgproc/Binary.hpp"
#include "imgproc/Gray.hpp"

namespace ocr {
namespace {

/// 画面外留这么多余量之外的角点直接判为坏四边形。
constexpr float kCornerMargin = 0.08f;

bool intersect(const FittedLine& horizontal, const FittedLine& vertical, PointF& out) {
    const double denominator = 1.0 - static_cast<double>(vertical.slope) * horizontal.slope;
    if (std::abs(denominator) < 1e-4) return false;
    const double x =
        (static_cast<double>(vertical.slope) * horizontal.intercept + vertical.intercept) / denominator;
    const double y = static_cast<double>(horizontal.slope) * x + horizontal.intercept;
    if (!std::isfinite(x) || !std::isfinite(y)) return false;
    out.x = static_cast<float>(x);
    out.y = static_cast<float>(y);
    return true;
}

float distance(const PointF& a, const PointF& b) { return std::hypot(a.x - b.x, a.y - b.y); }

bool isRightAngle(const PointF& topLeft, const PointF& topRight, const PointF& bottomLeft) {
    const double ax = static_cast<double>(topRight.x) - topLeft.x;
    const double ay = static_cast<double>(topRight.y) - topLeft.y;
    const double bx = static_cast<double>(bottomLeft.x) - topLeft.x;
    const double by = static_cast<double>(bottomLeft.y) - topLeft.y;
    const double lengthA = std::hypot(ax, ay);
    const double lengthB = std::hypot(bx, by);
    if (lengthA < 1e-3 || lengthB < 1e-3) return false;
    const double cosine = std::abs(ax * bx + ay * by) / (lengthA * lengthB);
    return cosine < 0.25;
}

/// 最外圈那条线没被检测到时，画面会被裁掉一整列；这里找框外还有没有内容。
void expandToInk(const Image8& src, PointF corners[4]) {
    const std::vector<uint8_t> gray = toGray(src);
    if (gray.empty()) return;
    const std::vector<uint8_t> binary = adaptiveThresholdMean(gray, src.width, src.height, 15, 10.0);

    const int width = src.width;
    const int height = src.height;
    const int top = std::clamp(static_cast<int>(std::min(corners[0].y, corners[1].y)), 0, height - 1);
    const int bottom = std::clamp(static_cast<int>(std::max(corners[3].y, corners[2].y)), top + 1, height);
    const int left = std::clamp(static_cast<int>(std::min(corners[0].x, corners[3].x)), 0, width - 1);
    const int right = std::clamp(static_cast<int>(std::max(corners[1].x, corners[2].x)), left + 1, width);

    std::vector<int> columnInk(static_cast<size_t>(width), 0);
    std::vector<int> rowInk(static_cast<size_t>(height), 0);
    for (int y = top; y < bottom; ++y) {
        const size_t row = static_cast<size_t>(y) * static_cast<size_t>(width);
        for (int x = 0; x < width; ++x) {
            if (binary[row + static_cast<size_t>(x)] != 0) ++columnInk[static_cast<size_t>(x)];
        }
    }
    // 纵向也数一遍：表格被截在画面边缘时最上面一两行落在首线之外
    for (int y = 0; y < height; ++y) {
        const size_t row = static_cast<size_t>(y) * static_cast<size_t>(width);
        int ink = 0;
        for (int x = 0; x < width; ++x) {
            if (binary[row + static_cast<size_t>(x)] != 0) ++ink;
        }
        rowInk[static_cast<size_t>(y)] = ink;
    }

    const int band = bottom - top;
    const int minInk = std::max(3, static_cast<int>(band * 0.01));
    int contentLeft = left;
    for (int x = 0; x < left; ++x) {
        if (columnInk[static_cast<size_t>(x)] >= minInk) {
            contentLeft = x;
            break;
        }
    }
    int contentRight = right;
    for (int x = width - 1; x >= right; --x) {
        if (columnInk[static_cast<size_t>(x)] >= minInk) {
            contentRight = x + 1;
            break;
        }
    }
    const int minRowInk = std::max(3, static_cast<int>(width * 0.02));
    int contentTop = top;
    for (int y = 0; y < top; ++y) {
        if (rowInk[static_cast<size_t>(y)] >= minRowInk) {
            contentTop = y;
            break;
        }
    }
    int contentBottom = bottom;
    for (int y = height - 1; y >= bottom; --y) {
        if (rowInk[static_cast<size_t>(y)] >= minRowInk) {
            contentBottom = y + 1;
            break;
        }
    }

    const int pad = std::max(4, width / 200);
    const bool expandLeft = left - contentLeft > pad;
    const bool expandRight = contentRight - right > pad;
    // 只容忍「少检测到一条边」；再多说明框外是桌面、键盘，框进来会多出垃圾列
    const int maxExpandX = std::max(24, static_cast<int>(width * 0.06));
    const int maxExpandY = std::max(24, static_cast<int>(height * 0.08));
    const bool overflows = (expandLeft && left - contentLeft > maxExpandX) ||
                           (expandRight && contentRight - right > maxExpandX) ||
                           (top - contentTop) > maxExpandY || (contentBottom - bottom) > maxExpandY;
    if (overflows) return;

    const float newLeft = static_cast<float>(std::max(0, contentLeft - pad));
    const float newRight = static_cast<float>(std::min(width, contentRight + pad));
    const bool expandTop = (top - contentTop) > pad;
    const bool expandBottom = (contentBottom - bottom) > pad;
    const float newTop = static_cast<float>(std::max(0, contentTop - pad));
    const float newBottom = static_cast<float>(std::min(height, contentBottom + pad));

    corners[0] = PointF{expandLeft ? newLeft : corners[0].x, expandTop ? newTop : corners[0].y};
    corners[1] = PointF{expandRight ? newRight : corners[1].x, expandTop ? newTop : corners[1].y};
    corners[2] = PointF{expandRight ? newRight : corners[2].x, expandBottom ? newBottom : corners[2].y};
    corners[3] = PointF{expandLeft ? newLeft : corners[3].x, expandBottom ? newBottom : corners[3].y};
}

}  // namespace

std::optional<TableQuad> fitTableQuad(const Image8& src, const LineDetection& detection) {
    if (!detection.usable() || src.empty()) return std::nullopt;

    const float centerX = static_cast<float>(detection.width) / 2.0f;
    const float centerY = static_cast<float>(detection.height) / 2.0f;

    const FittedLine* top = nullptr;
    const FittedLine* bottom = nullptr;
    const FittedLine* left = nullptr;
    const FittedLine* right = nullptr;
    for (const auto& line : detection.horizontal) {
        if (top == nullptr || line.valueAt(centerX) < top->valueAt(centerX)) top = &line;
        if (bottom == nullptr || line.valueAt(centerX) > bottom->valueAt(centerX)) bottom = &line;
    }
    for (const auto& line : detection.vertical) {
        if (left == nullptr || line.valueAt(centerY) < left->valueAt(centerY)) left = &line;
        if (right == nullptr || line.valueAt(centerY) > right->valueAt(centerY)) right = &line;
    }
    if (top == nullptr || bottom == nullptr || left == nullptr || right == nullptr) return std::nullopt;

    TableQuad quad;
    if (!intersect(*top, *left, quad.corners[0])) return std::nullopt;
    if (!intersect(*top, *right, quad.corners[1])) return std::nullopt;
    if (!intersect(*bottom, *right, quad.corners[2])) return std::nullopt;
    if (!intersect(*bottom, *left, quad.corners[3])) return std::nullopt;

    const float width = static_cast<float>(src.width);
    const float height = static_cast<float>(src.height);
    const float marginX = width * kCornerMargin;
    const float marginY = height * kCornerMargin;
    for (const auto& corner : quad.corners) {
        if (corner.x < -marginX || corner.x > width + marginX || corner.y < -marginY ||
            corner.y > height + marginY) {
            return std::nullopt;
        }
    }

    expandToInk(src, quad.corners);

    const int targetWidth = static_cast<int>(std::lround(
        (distance(quad.corners[0], quad.corners[1]) + distance(quad.corners[3], quad.corners[2])) / 2.0f));
    const int targetHeight = static_cast<int>(std::lround(
        (distance(quad.corners[0], quad.corners[3]) + distance(quad.corners[1], quad.corners[2])) / 2.0f));
    if (targetWidth < 200 || targetHeight < 150 || targetWidth > 4000 || targetHeight > 4000) {
        return std::nullopt;
    }
    // 角度太歪说明四条线不是同一张表的外框
    if (!isRightAngle(quad.corners[0], quad.corners[1], quad.corners[3])) return std::nullopt;

    return quad;
}

}  // namespace ocr
