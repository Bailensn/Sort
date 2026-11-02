#include "imgproc/Enhance.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

namespace ocr {
namespace {

std::vector<uint8_t> buildLut(int tilePixels, double clipLimit, const std::vector<int>& histogram) {
    std::vector<int> hist = histogram;
    const int limit = std::max(1, static_cast<int>(clipLimit * tilePixels / 256.0));
    int excess = 0;
    for (int& value : hist) {
        if (value > limit) {
            excess += value - limit;
            value = limit;
        }
    }
    // 溢出量平均补回整块，再累加成映射表
    const int share = excess / 256;
    for (int& value : hist) value += share;

    std::vector<uint8_t> lut(256, 0);
    long long total = 0;
    for (int value : hist) total += value;
    if (total <= 0) {
        for (int i = 0; i < 256; ++i) lut[static_cast<size_t>(i)] = static_cast<uint8_t>(i);
        return lut;
    }
    long long running = 0;
    for (int i = 0; i < 256; ++i) {
        running += hist[static_cast<size_t>(i)];
        const int mapped = static_cast<int>(running * 255 / total);
        lut[static_cast<size_t>(i)] = static_cast<uint8_t>(std::clamp(mapped, 0, 255));
    }
    return lut;
}

}  // namespace

void clahe(std::vector<uint8_t>& gray, int width, int height, double clipLimit, int tiles) {
    if (width <= 0 || height <= 0 || tiles <= 0) return;
    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (gray.size() < pixels) return;

    const int tileW = std::max(1, width / tiles);
    const int tileH = std::max(1, height / tiles);
    const int cols = (width + tileW - 1) / tileW;
    const int rows = (height + tileH - 1) / tileH;

    std::vector<std::vector<uint8_t>> luts;
    luts.reserve(static_cast<size_t>(rows) * static_cast<size_t>(cols));
    for (int ty = 0; ty < rows; ++ty) {
        for (int tx = 0; tx < cols; ++tx) {
            std::vector<int> hist(256, 0);
            int count = 0;
            const int x1 = std::min(width, (tx + 1) * tileW);
            const int y1 = std::min(height, (ty + 1) * tileH);
            for (int y = ty * tileH; y < y1; ++y) {
                for (int x = tx * tileW; x < x1; ++x) {
                    ++hist[gray[static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)]];
                    ++count;
                }
            }
            luts.push_back(buildLut(count, clipLimit, hist));
        }
    }

    std::vector<uint8_t> out(pixels, 0);
    for (int y = 0; y < height; ++y) {
        const int ty = std::min(rows - 1, y / tileH);
        for (int x = 0; x < width; ++x) {
            const int tx = std::min(cols - 1, x / tileW);
            const auto& lut = luts[static_cast<size_t>(ty) * static_cast<size_t>(cols) + static_cast<size_t>(tx)];
            out[static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)] =
                lut[gray[static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)]];
        }
    }
    gray.swap(out);
}

void rotateGray(std::vector<uint8_t>& gray, int& width, int& height, double degrees) {
    if (width <= 0 || height <= 0) return;
    if (std::abs(degrees) < 1e-6) return;
    // 转完要装得下，边界框按 |cos|、|sin| 放大
    const double rad = degrees * M_PI / 180.0;
    const double c = std::abs(std::cos(rad));
    const double s = std::abs(std::sin(rad));
    // 四舍五入而不是 ceil：cos(90°) 是 6e-17，ceil 会多出整整一个像素
    const int newW = static_cast<int>(std::lround(width * c + height * s));
    const int newH = static_cast<int>(std::lround(width * s + height * c));
    if (newW <= 0 || newH <= 0) return;
    if (static_cast<long long>(newW) * newH > 60000000LL) return;

    std::vector<uint8_t> out(static_cast<size_t>(newW) * static_cast<size_t>(newH), 255);
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
            const double sx = cosA * dx - sinA * dy + cx;
            const double sy = sinA * dx + cosA * dy + cy;
            if (sx < 0 || sy < 0 || sx >= width - 1 || sy >= height - 1) continue;

            const int x0 = static_cast<int>(sx);
            const int y0 = static_cast<int>(sy);
            const double fx = sx - x0;
            const double fy = sy - y0;
            const size_t i00 = static_cast<size_t>(y0) * static_cast<size_t>(width) + static_cast<size_t>(x0);
            const double v = gray[i00] * (1 - fx) * (1 - fy) + gray[i00 + 1] * fx * (1 - fy) +
                             gray[i00 + static_cast<size_t>(width)] * (1 - fx) * fy +
                             gray[i00 + static_cast<size_t>(width) + 1] * fx * fy;
            out[static_cast<size_t>(y) * static_cast<size_t>(newW) + static_cast<size_t>(x)] =
                static_cast<uint8_t>(std::clamp(static_cast<int>(std::lround(v)), 0, 255));
        }
    }
    gray.swap(out);
    width = newW;
    height = newH;
}

}  // namespace ocr
