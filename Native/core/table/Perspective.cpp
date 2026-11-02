#include "table/Perspective.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

namespace ocr {
namespace {

/// 解 8 元线性方程组，失败返回 false（退化四边形）。
bool solve(std::vector<std::vector<double>> a, std::vector<double> b, std::vector<double>& out) {
    const size_t n = a.size();
    for (size_t col = 0; col < n; ++col) {
        size_t pivot = col;
        for (size_t row = col + 1; row < n; ++row) {
            if (std::abs(a[row][col]) > std::abs(a[pivot][col])) pivot = row;
        }
        if (std::abs(a[pivot][col]) < 1e-9) return false;
        std::swap(a[col], a[pivot]);
        std::swap(b[col], b[pivot]);

        for (size_t row = 0; row < n; ++row) {
            if (row == col) continue;
            const double factor = a[row][col] / a[col][col];
            if (factor == 0) continue;
            for (size_t k = col; k < n; ++k) a[row][k] -= factor * a[col][k];
            b[row] -= factor * b[col];
        }
    }
    out.resize(n);
    for (size_t i = 0; i < n; ++i) out[i] = b[i] / a[i][i];
    return true;
}

/// 求把 dst 四点映到 src 四点的单应矩阵（反向取样用）。
bool homography(const PointF dst[4], const PointF src[4], double h[9]) {
    std::vector<std::vector<double>> a;
    std::vector<double> b;
    for (int i = 0; i < 4; ++i) {
        const double x = dst[i].x;
        const double y = dst[i].y;
        const double u = src[i].x;
        const double v = src[i].y;
        a.push_back({x, y, 1, 0, 0, 0, -u * x, -u * y});
        b.push_back(u);
        a.push_back({0, 0, 0, x, y, 1, -v * x, -v * y});
        b.push_back(v);
    }
    std::vector<double> solved;
    if (!solve(a, b, solved)) return false;
    for (int i = 0; i < 8; ++i) h[i] = solved[static_cast<size_t>(i)];
    h[8] = 1;
    return true;
}

}  // namespace

Image8 rectifyPerspective(const Image8& src, const PointF quad[4]) {
    if (src.width <= 0 || src.height <= 0 || src.data.empty()) return src;

    const auto distance = [](const PointF& a, const PointF& b) {
        return std::hypot(a.x - b.x, a.y - b.y);
    };
    const int width = static_cast<int>(std::lround(std::max(distance(quad[0], quad[1]),
                                                            distance(quad[3], quad[2]))));
    const int height = static_cast<int>(std::lround(std::max(distance(quad[0], quad[3]),
                                                             distance(quad[1], quad[2]))));
    if (width < 8 || height < 8 || static_cast<long long>(width) * height > 60000000LL) return src;

    PointF dst[4] = {{0, 0}, {static_cast<float>(width), 0},
                     {static_cast<float>(width), static_cast<float>(height)}, {0, static_cast<float>(height)}};
    double h[9];
    if (!homography(dst, quad, h)) return src;

    Image8 out;
    out.width = width;
    out.height = height;
    out.order = src.order;
    out.data.assign(static_cast<size_t>(width) * static_cast<size_t>(height) * 3, 255);

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const double denom = h[6] * x + h[7] * y + h[8];
            if (std::abs(denom) < 1e-9) continue;
            const double sx = (h[0] * x + h[1] * y + h[2]) / denom;
            const double sy = (h[3] * x + h[4] * y + h[5]) / denom;
            if (sx < 0 || sy < 0 || sx >= src.width - 1 || sy >= src.height - 1) continue;

            const int x0 = static_cast<int>(sx);
            const int y0 = static_cast<int>(sy);
            const double fx = sx - x0;
            const double fy = sy - y0;
            const size_t i00 = (static_cast<size_t>(y0) * static_cast<size_t>(src.width) + static_cast<size_t>(x0)) * 3;
            for (int c = 0; c < 3; ++c) {
                const double v = src.data[i00 + c] * (1 - fx) * (1 - fy) +
                                 src.data[i00 + 3 + c] * fx * (1 - fy) +
                                 src.data[i00 + static_cast<size_t>(src.width) * 3 + c] * (1 - fx) * fy +
                                 src.data[i00 + static_cast<size_t>(src.width) * 3 + 3 + c] * fx * fy;
                out.data[(static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)) * 3 + c] =
                    static_cast<uint8_t>(std::clamp(std::lround(v), 0L, 255L));
            }
        }
    }
    return out;
}

}  // namespace ocr
