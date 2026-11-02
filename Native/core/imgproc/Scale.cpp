#include "imgproc/Scale.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

namespace ocr {

std::vector<uint8_t> resizeGrayArea(const std::vector<uint8_t>& src, int width, int height,
                                    int dstWidth, int dstHeight) {
    if (width <= 0 || height <= 0 || dstWidth <= 0 || dstHeight <= 0) return {};
    if (static_cast<size_t>(width) * static_cast<size_t>(height) > src.size()) return {};
    if (width == dstWidth && height == dstHeight) return src;

    std::vector<uint8_t> out(static_cast<size_t>(dstWidth) * static_cast<size_t>(dstHeight), 0);
    const double scaleX = static_cast<double>(width) / dstWidth;
    const double scaleY = static_cast<double>(height) / dstHeight;

    for (int y = 0; y < dstHeight; ++y) {
        const int y0 = std::clamp(static_cast<int>(std::floor(y * scaleY)), 0, height - 1);
        const int y1 = std::clamp(static_cast<int>(std::ceil((y + 1) * scaleY)), y0 + 1, height);
        for (int x = 0; x < dstWidth; ++x) {
            const int x0 = std::clamp(static_cast<int>(std::floor(x * scaleX)), 0, width - 1);
            const int x1 = std::clamp(static_cast<int>(std::ceil((x + 1) * scaleX)), x0 + 1, width);

            long long sum = 0;
            int count = 0;
            for (int sy = y0; sy < y1; ++sy) {
                const size_t row = static_cast<size_t>(sy) * static_cast<size_t>(width);
                for (int sx = x0; sx < x1; ++sx) {
                    sum += src[row + static_cast<size_t>(sx)];
                    ++count;
                }
            }
            out[static_cast<size_t>(y) * static_cast<size_t>(dstWidth) + static_cast<size_t>(x)] =
                count > 0 ? static_cast<uint8_t>(sum / count) : 0;
        }
    }
    return out;
}

}  // namespace ocr
