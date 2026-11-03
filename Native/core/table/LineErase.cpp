#include "table/LineErase.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

#include "imgproc/Gray.hpp"
#include "imgproc/Threshold.hpp"

namespace ocr {
namespace {

void whiten(Image8& image, int x, int y) {
    if (x < 0 || y < 0 || x >= image.width || y >= image.height) return;
    uint8_t* pixel = image.row(y) + static_cast<size_t>(x) * 3u;
    pixel[0] = 255;
    pixel[1] = 255;
    pixel[2] = 255;
}

}  // namespace

Image8 eraseLines(const Image8& src, const LineDetection& detection, int halfWidth) {
    Image8 out = src;
    if (src.empty()) return out;

    const int half = std::max(0, halfWidth);

    // 一条线得真的横贯大半张纸才抹：整行文字被检成「线」时抹掉就是毁数据。
    const std::vector<uint8_t> gray = toGray(src);
    const int threshold = std::min(200, otsuThreshold(gray));
    auto dark = [&gray, &src, threshold](int x, int y) {
        if (x < 0 || y < 0 || x >= src.width || y >= src.height) return false;
        return gray[static_cast<size_t>(y) * static_cast<size_t>(src.width) + static_cast<size_t>(x)] <
               threshold;
    };
    auto covered = [&dark](const FittedLine& line, int span, bool horizontal, int limit) {
        const int step = std::max(1, span / 80);
        int hit = 0;
        int total = 0;
        for (int t = 0; t < span; t += step) {
            const int at = static_cast<int>(std::lround(line.valueAt(static_cast<float>(t))));
            bool found = false;
            for (int d = -1; d <= 1 && !found; ++d) {
                found = horizontal ? dark(t, at + d) : dark(at + d, t);
            }
            if (found) ++hit;
            ++total;
            if (at < 0 || at >= limit) break;
        }
        return total > 0 && static_cast<double>(hit) / total >= 0.6;
    };

    // 只沿线的法向加宽：横线上下各抹 half 行，竖线左右各抹 half 列。
    for (const auto& line : detection.horizontal) {
        if (!covered(line, src.width, true, src.height)) continue;
        for (int x = 0; x < src.width; ++x) {
            const int y = static_cast<int>(std::lround(line.valueAt(static_cast<float>(x))));
            for (int dy = -half; dy <= half; ++dy) whiten(out, x, y + dy);
        }
    }
    for (const auto& line : detection.vertical) {
        if (!covered(line, src.height, false, src.width)) continue;
        for (int y = 0; y < src.height; ++y) {
            const int x = static_cast<int>(std::lround(line.valueAt(static_cast<float>(y))));
            for (int dx = -half; dx <= half; ++dx) whiten(out, x + dx, y);
        }
    }
    return out;
}

}  // namespace ocr
