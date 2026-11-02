#include "imgproc/Warp.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

namespace ocr {

Image8 cropImage(const Image8& src, const Rect& rect) {
    Image8 out;
    if (src.empty() || rect.width() <= 0 || rect.height() <= 0) return out;

    out.width = rect.width();
    out.height = rect.height();
    out.order = src.order;
    out.data.assign(static_cast<size_t>(out.width) * static_cast<size_t>(out.height) * 3u, 255);

    for (int y = 0; y < out.height; ++y) {
        const int sy = rect.top + y;
        if (sy < 0 || sy >= src.height) continue;
        for (int x = 0; x < out.width; ++x) {
            const int sx = rect.left + x;
            if (sx < 0 || sx >= src.width) continue;
            const size_t from = (static_cast<size_t>(sy) * static_cast<size_t>(src.width) +
                                 static_cast<size_t>(sx)) * 3u;
            const size_t to = (static_cast<size_t>(y) * static_cast<size_t>(out.width) +
                               static_cast<size_t>(x)) * 3u;
            out.data[to] = src.data[from];
            out.data[to + 1] = src.data[from + 1];
            out.data[to + 2] = src.data[from + 2];
        }
    }
    return out;
}

Image8 grayToImage(const std::vector<uint8_t>& gray, int width, int height) {
    Image8 out;
    if (width <= 0 || height <= 0) return out;
    if (static_cast<size_t>(width) * static_cast<size_t>(height) > gray.size()) return out;

    out.width = width;
    out.height = height;
    out.order = ColorOrder::RGB;
    out.data.resize(static_cast<size_t>(width) * static_cast<size_t>(height) * 3u);
    for (size_t i = 0; i < static_cast<size_t>(width) * static_cast<size_t>(height); ++i) {
        out.data[i * 3] = gray[i];
        out.data[i * 3 + 1] = gray[i];
        out.data[i * 3 + 2] = gray[i];
    }
    return out;
}

Image8 rotateImage(const Image8& src, double degrees) {
    if (src.empty() || std::abs(degrees) < 1e-6) return src;

    const double rad = degrees * M_PI / 180.0;
    const double c = std::abs(std::cos(rad));
    const double s = std::abs(std::sin(rad));
    const int newW = static_cast<int>(std::lround(src.width * c + src.height * s));
    const int newH = static_cast<int>(std::lround(src.width * s + src.height * c));
    if (newW <= 0 || newH <= 0) return src;
    if (static_cast<long long>(newW) * newH > 60000000LL) return src;

    Image8 out;
    out.width = newW;
    out.height = newH;
    out.order = src.order;
    out.data.assign(static_cast<size_t>(newW) * static_cast<size_t>(newH) * 3u, 255);

    const double cx = src.width / 2.0;
    const double cy = src.height / 2.0;
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
            if (sx < 0 || sy < 0 || sx >= src.width - 1 || sy >= src.height - 1) continue;

            const int x0 = static_cast<int>(sx);
            const int y0 = static_cast<int>(sy);
            const double fx = sx - x0;
            const double fy = sy - y0;
            const size_t i00 = (static_cast<size_t>(y0) * static_cast<size_t>(src.width) +
                                static_cast<size_t>(x0)) * 3u;
            const size_t i10 = i00 + 3;
            const size_t i01 = i00 + static_cast<size_t>(src.width) * 3u;
            const size_t i11 = i01 + 3;
            const size_t to = (static_cast<size_t>(y) * static_cast<size_t>(newW) +
                               static_cast<size_t>(x)) * 3u;
            for (int channel = 0; channel < 3; ++channel) {
                const double value =
                    src.data[i00 + channel] * (1 - fx) * (1 - fy) +
                    src.data[i10 + channel] * fx * (1 - fy) +
                    src.data[i01 + channel] * (1 - fx) * fy +
                    src.data[i11 + channel] * fx * fy;
                out.data[to + channel] =
                    static_cast<uint8_t>(std::clamp(static_cast<int>(std::lround(value)), 0, 255));
            }
        }
    }
    return out;
}

}  // namespace ocr
