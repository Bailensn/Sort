#include "DbPostprocess.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

#include "ImageOps.hpp"
#include "Geometry.hpp"
#include "Postprocess.hpp"

namespace ocr {
namespace {

constexpr float kMinSizeBeforeUnclip = 3.0f;
constexpr float kMinSizeAfterUnclip = 5.0f;
constexpr int kDilateSize = 2;

inline size_t offsetOf(int x, int y, int width) {
    return static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x);
}

bool insideMask(const std::vector<uint8_t>& mask, int width, int height, int x, int y) {
    if (x < 0 || y < 0 || x >= width || y >= height) return false;
    return mask[offsetOf(x, y, width)] != 0;
}

/// 8 邻域边界跟踪；只收外轮廓，起点按扫描序。
std::vector<PointF> traceContour(const std::vector<uint8_t>& mask, int width, int height,
                                 std::vector<uint8_t>& visited, int startX, int startY) {
    static const int kDx[8] = {1, 1, 0, -1, -1, -1, 0, 1};
    static const int kDy[8] = {0, 1, 1, 1, 0, -1, -1, -1};

    std::vector<PointF> contour;
    int x = startX;
    int y = startY;
    int direction = 7;  // 从左上开始找下一个边界点
    const int maxSteps = width * height * 4;

    for (int step = 0; step < maxSteps; ++step) {
        contour.push_back(PointF{static_cast<float>(x), static_cast<float>(y)});
        visited[offsetOf(x, y, width)] = 1;

        bool found = false;
        int probe = (direction + 5) % 8;  // 从上一个方向的反向开始扫
        for (int k = 0; k < 8; ++k) {
            const int dir = (probe + k) % 8;
            const int nx = x + kDx[dir];
            const int ny = y + kDy[dir];
            if (insideMask(mask, width, height, nx, ny)) {
                x = nx;
                y = ny;
                direction = dir;
                found = true;
                break;
            }
        }
        if (!found) break;
        if (x == startX && y == startY) break;
    }
    return contour;
}

std::vector<PointF> approximateChain(const std::vector<PointF>& contour);

/// 孔洞：从图像边界洪泛不到的背景区域，OpenCV 的 RETR_LIST 也会把它们当轮廓。
std::vector<std::vector<PointF>> traceHoles(const std::vector<uint8_t>& mask, int width,
                                            int height) {
    std::vector<uint8_t> reachable(mask.size(), 0);
    std::vector<int> stack;

    auto pushIfBackground = [&](int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        const size_t index = offsetOf(x, y, width);
        if (mask[index] != 0 || reachable[index] != 0) return;
        reachable[index] = 1;
        stack.push_back(static_cast<int>(index));
    };

    for (int x = 0; x < width; ++x) {
        pushIfBackground(x, 0);
        pushIfBackground(x, height - 1);
    }
    for (int y = 0; y < height; ++y) {
        pushIfBackground(0, y);
        pushIfBackground(width - 1, y);
    }

    while (!stack.empty()) {
        const int index = stack.back();
        stack.pop_back();
        const int x = static_cast<int>(static_cast<size_t>(index) % static_cast<size_t>(width));
        const int y = static_cast<int>(static_cast<size_t>(index) / static_cast<size_t>(width));
        pushIfBackground(x + 1, y);
        pushIfBackground(x - 1, y);
        pushIfBackground(x, y + 1);
        pushIfBackground(x, y - 1);
    }

    std::vector<uint8_t> holeMask(mask.size(), 0);
    for (size_t i = 0; i < mask.size(); ++i) {
        if (mask[i] == 0 && reachable[i] == 0) holeMask[i] = 1;
    }

    std::vector<uint8_t> visited(holeMask.size(), 0);
    std::vector<std::vector<PointF>> holes;
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const size_t index = offsetOf(x, y, width);
            if (holeMask[index] == 0 || visited[index] != 0) continue;
            std::vector<PointF> contour = traceContour(holeMask, width, height, visited, x, y);
            if (contour.size() >= 3) holes.push_back(approximateChain(contour));
        }
    }
    return holes;
}

/// CHAIN_APPROX_SIMPLE：只留转折点。
std::vector<PointF> approximateChain(const std::vector<PointF>& contour) {
    if (contour.size() < 3) return contour;
    std::vector<PointF> out;
    out.push_back(contour.front());

    for (size_t i = 1; i + 1 < contour.size(); ++i) {
        const PointF& prev = contour[i - 1];
        const PointF& curr = contour[i];
        const PointF& next = contour[i + 1];
        const float dx1 = curr.x - prev.x;
        const float dy1 = curr.y - prev.y;
        const float dx2 = next.x - curr.x;
        const float dy2 = next.y - curr.y;
        const float cross = dx1 * dy2 - dy1 * dx2;
        const float dot = dx1 * dx2 + dy1 * dy2;
        if (std::fabs(cross) > 1e-6f || dot <= 0.0f) {
            out.push_back(curr);
        }
    }
    out.push_back(contour.back());
    return out;
}

bool pointInPolygon(const std::vector<PointF>& polygon, float x, float y) {
    bool inside = false;
    for (size_t i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
        const float yi = polygon[i].y;
        const float yj = polygon[j].y;
        const bool crosses = ((yi > y) != (yj > y));
        if (!crosses) continue;
        const float xi = polygon[i].x;
        const float xj = polygon[j].x;
        if (x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
    }
    return inside;
}

float boxScore(const Tensor& prob, int width, int height, const std::vector<PointF>& polygon) {
    if (polygon.empty()) return 0.0f;

    float minX = polygon[0].x;
    float maxX = polygon[0].x;
    float minY = polygon[0].y;
    float maxY = polygon[0].y;
    for (const PointF& point : polygon) {
        minX = std::min(minX, point.x);
        maxX = std::max(maxX, point.x);
        minY = std::min(minY, point.y);
        maxY = std::max(maxY, point.y);
    }

    const int x0 = std::max(0, static_cast<int>(std::floor(minX)));
    const int x1 = std::min(width - 1, static_cast<int>(std::ceil(maxX)));
    const int y0 = std::max(0, static_cast<int>(std::floor(minY)));
    const int y1 = std::min(height - 1, static_cast<int>(std::ceil(maxY)));
    if (x1 < x0 || y1 < y0) return 0.0f;

    double sum = 0.0;
    int count = 0;
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            if (!pointInPolygon(polygon, static_cast<float>(x) + 0.5f, static_cast<float>(y) + 0.5f)) continue;
            sum += prob.data[offsetOf(x, y, width)];
            ++count;
        }
    }
    return count > 0 ? static_cast<float>(sum / count) : 0.0f;
}

PointF scalePoint(const PointF& point, double scaleX, double scaleY, int originalW, int originalH) {
    PointF out;
    out.x = static_cast<float>(std::min(std::max(roundHalfToEven(point.x * scaleX), 0), originalW));
    out.y = static_cast<float>(std::min(std::max(roundHalfToEven(point.y * scaleY), 0), originalH));
    return out;
}

}  // namespace

std::vector<Box> dbPostprocess(const Tensor& prediction, int originalWidth, int originalHeight,
                               const OcrConfig& config) {
    std::vector<Box> boxes;
    if (prediction.dims.size() != 4) return boxes;

    const int height = static_cast<int>(prediction.dims[2]);
    const int width = static_cast<int>(prediction.dims[3]);
    if (width <= 0 || height <= 0) return boxes;

    const double scaleX = static_cast<double>(originalWidth) / width;
    const double scaleY = static_cast<double>(originalHeight) / height;

    std::vector<uint8_t> mask(static_cast<size_t>(width) * static_cast<size_t>(height), 0);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const float value = prediction.data[offsetOf(x, y, width)];
            mask[offsetOf(x, y, width)] = value > config.detThresh ? 255 : 0;
        }
    }

    if (config.detUseDilation) {
        std::vector<uint8_t> dilated(mask.size(), 0);
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                uint8_t best = 0;
                for (int ky = 0; ky < kDilateSize; ++ky) {
                    for (int kx = 0; kx < kDilateSize; ++kx) {
                        const int sx = std::min(x + kx, width - 1);
                        const int sy = std::min(y + ky, height - 1);
                        best = std::max(best, mask[offsetOf(sx, sy, width)]);
                    }
                }
                dilated[offsetOf(x, y, width)] = best;
            }
        }
        mask.swap(dilated);
    }

    std::vector<uint8_t> visited(mask.size(), 0);
    std::vector<std::vector<PointF>> contours;
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            if (mask[offsetOf(x, y, width)] == 0) continue;
            if (visited[offsetOf(x, y, width)]) continue;
            std::vector<PointF> contour = traceContour(mask, width, height, visited, x, y);
            if (contour.size() >= 3) {
                contours.push_back(approximateChain(contour));
            }
        }
    }

    const std::vector<std::vector<PointF>> holes = traceHoles(mask, width, height);
    contours.insert(contours.end(), holes.begin(), holes.end());

    const size_t limit = std::min<size_t>(contours.size(), static_cast<size_t>(config.detMaxCandidates));
    for (size_t index = 0; index < limit; ++index) {
        const std::vector<PointF>& contour = contours[index];

        const MinAreaRect rect = minAreaRect(contour);
        const float shortSide = std::min(rect.width, rect.height);
        if (shortSide < kMinSizeBeforeUnclip) continue;

        PointF corners[4];
        rectCorners(rect, corners);
        const Box ordered = orderQuad(corners);

        const std::vector<PointF> polygon(ordered.points, ordered.points + 4);
        // slow 模式按轮廓点算分，能扣掉四边形里不属于文字的部分。
        const float score = config.detScoreSlow ? boxScore(prediction, width, height, contour)
                                                : boxScore(prediction, width, height, polygon);
        if (score < config.detBoxThresh) continue;

        const std::vector<PointF> expanded = unclip(polygon, config.detUnclipRatio);
        const MinAreaRect expandedRect = minAreaRect(expanded);
        if (std::min(expandedRect.width, expandedRect.height) < kMinSizeAfterUnclip) continue;

        PointF expandedCorners[4];
        rectCorners(expandedRect, expandedCorners);
        const Box expandedBox = orderQuad(expandedCorners);

        Box scaled;
        for (int i = 0; i < 4; ++i) {
            scaled.points[i] = scalePoint(expandedBox.points[i], scaleX, scaleY, originalWidth,
                                          originalHeight);
        }

        const double boxW = std::hypot(scaled.points[1].x - scaled.points[0].x,
                                       scaled.points[1].y - scaled.points[0].y);
        const double boxH = std::hypot(scaled.points[3].x - scaled.points[0].x,
                                       scaled.points[3].y - scaled.points[0].y);
        if (boxW <= 3.0 || boxH <= 3.0) continue;

        boxes.push_back(scaled);
    }
    return boxes;
}

}  // namespace ocr
