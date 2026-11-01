#include "QuadCrop.hpp"

#include <algorithm>
#include <cmath>

#include "Geometry.hpp"
#include "ImageOps.hpp"
#include "Postprocess.hpp"

namespace ocr {
namespace {

constexpr double kVerticalCropRatio = 1.5;

bool solve8(double matrix[8][9]) {
    for (int col = 0; col < 8; ++col) {
        int pivot = col;
        for (int row = col + 1; row < 8; ++row) {
            if (std::fabs(matrix[row][col]) > std::fabs(matrix[pivot][col])) pivot = row;
        }
        if (std::fabs(matrix[pivot][col]) < 1e-12) return false;
        if (pivot != col) {
            for (int k = col; k < 9; ++k) std::swap(matrix[pivot][k], matrix[col][k]);
        }
        const double divisor = matrix[col][col];
        for (int k = col; k < 9; ++k) matrix[col][k] /= divisor;
        for (int row = 0; row < 8; ++row) {
            if (row == col) continue;
            const double factor = matrix[row][col];
            if (factor == 0.0) continue;
            for (int k = col; k < 9; ++k) matrix[row][k] -= factor * matrix[col][k];
        }
    }
    return true;
}

Image8 rotate90CounterClockwise(const Image8& src) {
    Image8 dst;
    dst.width = src.height;
    dst.height = src.width;
    dst.order = src.order;
    dst.data.resize(src.data.size());

    // 原本的左上角转到左下角，等价于 dst(x,y) = src(W-1-y, x)。
    for (int y = 0; y < dst.height; ++y) {
        for (int x = 0; x < dst.width; ++x) {
            const int sx = src.width - 1 - y;
            const int sy = x;
            const uint8_t* p = src.row(sy) + static_cast<size_t>(sx) * 3;
            uint8_t* q = dst.row(y) + static_cast<size_t>(x) * 3;
            q[0] = p[0];
            q[1] = p[1];
            q[2] = p[2];
        }
    }
    return dst;
}

}  // namespace

bool perspectiveTransform(const PointF src[4], const PointF dst[4], float matrix[9]) {
    double a[8][9] = {};

    for (int i = 0; i < 4; ++i) {
        const double x = src[i].x;
        const double y = src[i].y;
        const double u = dst[i].x;
        const double v = dst[i].y;
        a[i * 2][0] = x;
        a[i * 2][1] = y;
        a[i * 2][2] = 1.0;
        a[i * 2][6] = -u * x;
        a[i * 2][7] = -u * y;
        a[i * 2][8] = u;

        a[i * 2 + 1][3] = x;
        a[i * 2 + 1][4] = y;
        a[i * 2 + 1][5] = 1.0;
        a[i * 2 + 1][6] = -v * x;
        a[i * 2 + 1][7] = -v * y;
        a[i * 2 + 1][8] = v;
    }

    if (!solve8(a)) return false;
    for (int i = 0; i < 8; ++i) matrix[i] = static_cast<float>(a[i][8]);
    matrix[8] = 1.0f;
    return true;
}

Image8 quadCrop(const Image8& src, const Box& box) {
    if (src.empty()) return Image8{};

    const std::vector<PointF> points(box.points, box.points + 4);
    const MinAreaRect rect = minAreaRect(points);
    PointF corners[4];
    rectCorners(rect, corners);
    const Box ordered = orderQuad(corners);

    const double widthTop = std::hypot(ordered.points[0].x - ordered.points[1].x,
                                       ordered.points[0].y - ordered.points[1].y);
    const double widthBottom = std::hypot(ordered.points[2].x - ordered.points[3].x,
                                          ordered.points[2].y - ordered.points[3].y);
    const double heightLeft = std::hypot(ordered.points[0].x - ordered.points[3].x,
                                         ordered.points[0].y - ordered.points[3].y);
    const double heightRight = std::hypot(ordered.points[1].x - ordered.points[2].x,
                                          ordered.points[1].y - ordered.points[2].y);

    const int dstW = std::max(1, static_cast<int>(std::max(widthTop, widthBottom)));
    const int dstH = std::max(1, static_cast<int>(std::max(heightLeft, heightRight)));

    const PointF target[4] = {
        PointF{0.0f, 0.0f},
        PointF{static_cast<float>(dstW), 0.0f},
        PointF{static_cast<float>(dstW), static_cast<float>(dstH)},
        PointF{0.0f, static_cast<float>(dstH)},
    };

    float matrix[9] = {};
    if (!perspectiveTransform(ordered.points, target, matrix)) return Image8{};

    Image8 cropped = warpPerspective(src, matrix, dstW, dstH, Interp::Cubic);

    if (dstH > 0 && dstW > 0 && static_cast<double>(dstH) / dstW >= kVerticalCropRatio) {
        cropped = rotate90CounterClockwise(cropped);
    }
    return cropped;
}

}  // namespace ocr
