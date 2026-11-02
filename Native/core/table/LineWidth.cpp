#include "table/LineWidth.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

#include "imgproc/Binary.hpp"
#include "imgproc/Gray.hpp"

namespace ocr {
namespace {

constexpr int kRadius = 3;
constexpr int kSamples = 8;
constexpr int kFallback = 2;

}  // namespace

int estimateLineWidth(const Image8& src, const GridLines& lines) {
    if (src.empty()) return kFallback;

    const std::vector<uint8_t> gray = toGray(src);
    if (gray.empty()) return kFallback;
    // 和线检测用同一套光照归一化，暗处的线才不会被当成「这里没画线」
    std::vector<uint8_t> flattened = gray;
    flattenLighting(flattened, src.width, src.height);
    const std::vector<uint8_t> binary = adaptiveThresholdMean(flattened, src.width, src.height, 15, 10.0);

    auto dark = [&](int x, int y) {
        if (x < 0 || y < 0 || x >= src.width || y >= src.height) return false;
        return binary[static_cast<size_t>(y) * static_cast<size_t>(src.width) +
                      static_cast<size_t>(x)] > 127;
    };

    std::vector<int> runs;
    for (float line : lines.horizontal) {
        const int base = static_cast<int>(std::lround(line));
        for (int step = 1; step <= kSamples; ++step) {
            const int x = src.width * step / (kSamples + 1);
            int run = 0;
            for (int dy = -kRadius; dy <= kRadius; ++dy) {
                if (dark(x, base + dy)) ++run;
            }
            if (run > 0) runs.push_back(run);
        }
    }
    for (float line : lines.vertical) {
        const int base = static_cast<int>(std::lround(line));
        for (int step = 1; step <= kSamples; ++step) {
            const int y = src.height * step / (kSamples + 1);
            int run = 0;
            for (int dx = -kRadius; dx <= kRadius; ++dx) {
                if (dark(base + dx, y)) ++run;
            }
            if (run > 0) runs.push_back(run);
        }
    }
    if (runs.empty()) return kFallback;

    long long total = 0;
    for (int run : runs) total += run;
    return static_cast<int>(total / static_cast<long long>(runs.size()));
}

}  // namespace ocr
