#include "table/Overlay.hpp"

#include <algorithm>
#include <cmath>

namespace ocr {
namespace {

constexpr int kOverlayR = 0;
constexpr int kOverlayG = 150;
constexpr int kOverlayB = 136;
constexpr int kOverlayAlpha = 190;

void blendPixel(Image8& image, int x, int y) {
    if (x < 0 || y < 0 || x >= image.width || y >= image.height) return;
    uint8_t* pixel = image.row(y) + static_cast<size_t>(x) * 3u;
    const bool bgr = image.order == ColorOrder::BGR;
    const int target[3] = {bgr ? kOverlayB : kOverlayR, kOverlayG, bgr ? kOverlayR : kOverlayB};
    for (int channel = 0; channel < 3; ++channel) {
        const int base = pixel[channel];
        pixel[channel] = static_cast<uint8_t>(
            (base * (255 - kOverlayAlpha) + target[channel] * kOverlayAlpha) / 255);
    }
}

}  // namespace

Image8 drawGridOverlay(const Image8& src, const LineDetection& detection) {
    Image8 out = src;
    if (src.empty()) return out;

    const int radius = std::max(0, static_cast<int>(std::lround(std::max(1.5f, src.width / 700.0f) / 2.0f)));
    for (const auto& line : detection.horizontal) {
        for (int x = 0; x < src.width; ++x) {
            const int y = static_cast<int>(std::lround(line.valueAt(static_cast<float>(x))));
            for (int dy = -radius; dy <= radius; ++dy) blendPixel(out, x, y + dy);
        }
    }
    for (const auto& line : detection.vertical) {
        for (int y = 0; y < src.height; ++y) {
            const int x = static_cast<int>(std::lround(line.valueAt(static_cast<float>(y))));
            for (int dx = -radius; dx <= radius; ++dx) blendPixel(out, x + dx, y);
        }
    }
    return out;
}


Image8 drawBoxesOverlay(const Image8& src, const std::vector<Box>& boxes) {
    Image8 out = src;
    if (src.empty()) return out;

    const int radius =
        std::max(0, static_cast<int>(std::lround(std::max(1.5f, src.width / 700.0f) / 2.0f)));
    for (const auto& box : boxes) {
        for (int i = 0; i < 4; ++i) {
            const PointF a = box.points[i];
            const PointF b = box.points[(i + 1) % 4];
            const int steps =
                static_cast<int>(std::max(std::abs(b.x - a.x), std::abs(b.y - a.y)));
            for (int s = 0; s <= steps; ++s) {
                const float t = steps == 0 ? 0.0f : static_cast<float>(s) / static_cast<float>(steps);
                const int x = static_cast<int>(std::lround(a.x + (b.x - a.x) * t));
                const int y = static_cast<int>(std::lround(a.y + (b.y - a.y) * t));
                for (int d = -radius; d <= radius; ++d) {
                    blendPixel(out, x + d, y);
                    blendPixel(out, x, y + d);
                }
            }
        }
    }
    return out;
}

}  // namespace ocr
