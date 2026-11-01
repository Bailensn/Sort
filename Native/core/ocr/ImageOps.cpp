#include "ImageOps.hpp"

#include <cmath>
#include <limits>

namespace ocr {
namespace {

inline int clampInt(int value, int low, int high) {
    return value < low ? low : (value > high ? high : value);
}

inline const uint8_t* pixelAt(const Image8& image, int x, int y) {
    const int cx = clampInt(x, 0, image.width - 1);
    const int cy = clampInt(y, 0, image.height - 1);
    return image.row(cy) + static_cast<size_t>(cx) * 3;
}

inline double cubicWeight(double t) {
    constexpr double a = -0.75;
    const double x = std::fabs(t);
    if (x <= 1.0) {
        return ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0;
    }
    if (x < 2.0) {
        return ((a * x - 5.0 * a) * x + 8.0 * a) * x - 4.0 * a;
    }
    return 0.0;
}

/// 在浮点坐标处采样，越界按边缘复制，和 BORDER_REPLICATE 一致。
void sampleCubic(const Image8& image, double fx, double fy, double out[3]) {
    const int x0 = static_cast<int>(std::floor(fx));
    const int y0 = static_cast<int>(std::floor(fy));
    double sum[3] = {0.0, 0.0, 0.0};
    double weightSum = 0.0;
    for (int dy = -1; dy <= 2; ++dy) {
        const double wy = cubicWeight(fy - (y0 + dy));
        for (int dx = -1; dx <= 2; ++dx) {
            const double w = cubicWeight(fx - (x0 + dx)) * wy;
            const uint8_t* p = pixelAt(image, x0 + dx, y0 + dy);
            sum[0] += p[0] * w;
            sum[1] += p[1] * w;
            sum[2] += p[2] * w;
            weightSum += w;
        }
    }
    const double scale = weightSum != 0.0 ? 1.0 / weightSum : 0.0;
    for (int c = 0; c < 3; ++c) {
        out[c] = sum[c] * scale;
    }
}

void sampleLinear(const Image8& image, double fx, double fy, double out[3]) {
    const int x0 = static_cast<int>(std::floor(fx));
    const int y0 = static_cast<int>(std::floor(fy));
    const double ax = fx - x0;
    const double ay = fy - y0;
    const uint8_t* p00 = pixelAt(image, x0, y0);
    const uint8_t* p10 = pixelAt(image, x0 + 1, y0);
    const uint8_t* p01 = pixelAt(image, x0, y0 + 1);
    const uint8_t* p11 = pixelAt(image, x0 + 1, y0 + 1);
    for (int c = 0; c < 3; ++c) {
        const double top = p00[c] * (1.0 - ax) + p10[c] * ax;
        const double bottom = p01[c] * (1.0 - ax) + p11[c] * ax;
        out[c] = top * (1.0 - ay) + bottom * ay;
    }
}

inline uint8_t toByte(double value) {
    if (value <= 0.0) return 0;
    if (value >= 255.0) return 255;
    return static_cast<uint8_t>(value + 0.5);
}

inline void writePixel(Image8& image, int x, int y, const double values[3]) {
    uint8_t* p = image.row(y) + static_cast<size_t>(x) * 3;
    p[0] = toByte(values[0]);
    p[1] = toByte(values[1]);
    p[2] = toByte(values[2]);
}

}  // namespace

int roundHalfToEven(double value) {
    const double floored = std::floor(value);
    const double diff = value - floored;
    if (diff < 0.5) return static_cast<int>(floored);
    if (diff > 0.5) return static_cast<int>(floored) + 1;
    return static_cast<int>(floored) % 2 == 0 ? static_cast<int>(floored)
                                              : static_cast<int>(floored) + 1;
}

Image8 resize(const Image8& src, int dstWidth, int dstHeight, Interp interp) {
    if (src.empty() || dstWidth <= 0 || dstHeight <= 0) return Image8{};

    Image8 dst;
    dst.width = dstWidth;
    dst.height = dstHeight;
    dst.order = src.order;
    dst.data.resize(static_cast<size_t>(dstWidth) * static_cast<size_t>(dstHeight) * 3u);

    const double scaleX = static_cast<double>(src.width) / dstWidth;
    const double scaleY = static_cast<double>(src.height) / dstHeight;

    for (int y = 0; y < dstHeight; ++y) {
        const double sy = (y + 0.5) * scaleY - 0.5;
        for (int x = 0; x < dstWidth; ++x) {
            const double sx = (x + 0.5) * scaleX - 0.5;
            double values[3] = {0.0, 0.0, 0.0};
            if (interp == Interp::Nearest) {
                const uint8_t* p = pixelAt(src, static_cast<int>(std::lround(sx)),
                                           static_cast<int>(std::lround(sy)));
                values[0] = p[0];
                values[1] = p[1];
                values[2] = p[2];
            } else if (interp == Interp::Cubic) {
                sampleCubic(src, sx, sy, values);
            } else {
                sampleLinear(src, sx, sy, values);
            }
            writePixel(dst, x, y, values);
        }
    }
    return dst;
}

Image8 crop(const Image8& src, const Rect& roi) {
    const int left = clampInt(roi.left, 0, src.width);
    const int top = clampInt(roi.top, 0, src.height);
    const int right = clampInt(roi.right, left, src.width);
    const int bottom = clampInt(roi.bottom, top, src.height);
    if (right - left <= 0 || bottom - top <= 0) return Image8{};

    Image8 dst;
    dst.width = right - left;
    dst.height = bottom - top;
    dst.order = src.order;
    dst.data.resize(static_cast<size_t>(dst.width) * static_cast<size_t>(dst.height) * 3u);
    for (int y = 0; y < dst.height; ++y) {
        const uint8_t* srcRow = src.row(top + y) + static_cast<size_t>(left) * 3;
        uint8_t* dstRow = dst.row(y);
        for (int i = 0; i < dst.width * 3; ++i) {
            dstRow[i] = srcRow[i];
        }
    }
    return dst;
}

Image8 copyMakeBorder(const Image8& src, int top, int bottom, int left, int right, uint8_t value) {
    if (src.empty() || top < 0 || bottom < 0 || left < 0 || right < 0) return Image8{};

    Image8 dst;
    dst.width = src.width + left + right;
    dst.height = src.height + top + bottom;
    dst.order = src.order;
    dst.data.assign(static_cast<size_t>(dst.width) * static_cast<size_t>(dst.height) * 3u, value);

    for (int y = 0; y < src.height; ++y) {
        const uint8_t* srcRow = src.row(y);
        uint8_t* dstRow = dst.row(top + y) + static_cast<size_t>(left) * 3;
        for (int i = 0; i < src.width * 3; ++i) {
            dstRow[i] = srcRow[i];
        }
    }
    return dst;
}

Image8 warpPerspective(const Image8& src, const float matrix[9], int dstWidth, int dstHeight,
                       Interp interp) {
    if (src.empty() || dstWidth <= 0 || dstHeight <= 0) return Image8{};

    // 逆矩阵做 dst -> src 的反查，和 OpenCV 内部一致。
    const double a = matrix[0], b = matrix[1], c = matrix[2];
    const double d = matrix[3], e = matrix[4], f = matrix[5];
    const double g = matrix[6], h = matrix[7], i = matrix[8];

    const double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    if (std::fabs(det) < 1e-12) return Image8{};

    const double inv[9] = {
        (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
        (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
        (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
    };

    Image8 dst;
    dst.width = dstWidth;
    dst.height = dstHeight;
    dst.order = src.order;
    dst.data.resize(static_cast<size_t>(dstWidth) * static_cast<size_t>(dstHeight) * 3u);

    for (int y = 0; y < dstHeight; ++y) {
        for (int x = 0; x < dstWidth; ++x) {
            const double wx = inv[0] * x + inv[1] * y + inv[2];
            const double wy = inv[3] * x + inv[4] * y + inv[5];
            const double wz = inv[6] * x + inv[7] * y + inv[8];
            if (std::fabs(wz) < 1e-12) continue;

            double values[3] = {0.0, 0.0, 0.0};
            const double sx = wx / wz;
            const double sy = wy / wz;
            if (interp == Interp::Nearest) {
                const uint8_t* p = pixelAt(src, static_cast<int>(std::lround(sx)),
                                           static_cast<int>(std::lround(sy)));
                values[0] = p[0];
                values[1] = p[1];
                values[2] = p[2];
            } else if (interp == Interp::Cubic) {
                sampleCubic(src, sx, sy, values);
            } else {
                sampleLinear(src, sx, sy, values);
            }
            writePixel(dst, x, y, values);
        }
    }
    return dst;
}

Image8 swapRedBlue(const Image8& src) {
    Image8 dst = src;
    for (int y = 0; y < dst.height; ++y) {
        uint8_t* row = dst.row(y);
        for (int x = 0; x < dst.width; ++x) {
            const uint8_t tmp = row[x * 3];
            row[x * 3] = row[x * 3 + 2];
            row[x * 3 + 2] = tmp;
        }
    }
    dst.order = src.order == ColorOrder::BGR ? ColorOrder::RGB : ColorOrder::BGR;
    return dst;
}

bool hasInk(const Image8& src, double minRatio) {
    if (src.empty()) return false;

    // 单趟直方图，不先落一张灰度图：整表几百个格子时这里最费。
    int histogram[256] = {0};
    const bool isRgb = src.order == ColorOrder::RGB;
    for (int y = 0; y < src.height; ++y) {
        const uint8_t* row = src.row(y);
        for (int x = 0; x < src.width; ++x) {
            const uint8_t c0 = row[x * 3];
            const uint8_t g = row[x * 3 + 1];
            const uint8_t c2 = row[x * 3 + 2];
            const uint8_t r = isRgb ? c0 : c2;
            const uint8_t b = isRgb ? c2 : c0;
            ++histogram[toByte(0.299 * r + 0.587 * g + 0.114 * b)];
        }
    }

    const size_t total = static_cast<size_t>(src.width) * static_cast<size_t>(src.height);
    if (total == 0) return false;

    // Otsu：取类间方差最大的阈值，等价于 OpenCV 的 THRESH_OTSU。
    double sum = 0.0;
    for (int t = 0; t < 256; ++t) {
        sum += static_cast<double>(t) * histogram[t];
    }
    double sumB = 0.0;
    double weightB = 0.0;
    double bestVariance = -1.0;
    int threshold = 0;
    for (int t = 0; t < 256; ++t) {
        weightB += histogram[t];
        if (weightB == 0.0) continue;
        const double weightF = static_cast<double>(total) - weightB;
        if (weightF == 0.0) break;
        sumB += static_cast<double>(t) * histogram[t];
        const double meanB = sumB / weightB;
        const double meanF = (sum - sumB) / weightF;
        const double variance = weightB * weightF * (meanB - meanF) * (meanB - meanF);
        if (variance > bestVariance) {
            bestVariance = variance;
            threshold = t;
        }
    }

    size_t ink = 0;
    for (int t = 0; t <= threshold; ++t) {
        ink += static_cast<size_t>(histogram[t]);
    }
    return static_cast<double>(ink) / static_cast<double>(total) > minRatio;
}

}  // namespace ocr
