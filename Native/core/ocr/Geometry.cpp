#include "Geometry.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace ocr {
namespace {

float cross2d(const PointF& origin, const PointF& a, const PointF& b) {
    return (a.x - origin.x) * (b.y - origin.y) - (a.y - origin.y) * (b.x - origin.x);
}

}  // namespace

std::vector<PointF> convexHull(std::vector<PointF> points) {
    if (points.size() < 3) return points;

    std::sort(points.begin(), points.end(), [](const PointF& a, const PointF& b) {
        if (a.x != b.x) return a.x < b.x;
        return a.y < b.y;
    });

    std::vector<PointF> hull;
    for (const PointF& point : points) {
        while (hull.size() >= 2 && cross2d(hull[hull.size() - 2], hull.back(), point) <= 0) {
            hull.pop_back();
        }
        hull.push_back(point);
    }

    const size_t lower = hull.size();
    for (size_t i = points.size() - 1; i > 0; --i) {
        const PointF& point = points[i - 1];
        while (hull.size() > lower && cross2d(hull[hull.size() - 2], hull.back(), point) <= 0) {
            hull.pop_back();
        }
        hull.push_back(point);
    }
    hull.pop_back();
    return hull;
}

MinAreaRect minAreaRect(const std::vector<PointF>& points) {
    MinAreaRect rect;
    const std::vector<PointF> hull = convexHull(points);
    if (hull.size() < 2) return rect;

    // 旋转卡壳：对每条凸包边求投影包围盒，取面积最小者。
    float bestArea = std::numeric_limits<float>::max();
    for (size_t i = 0; i < hull.size(); ++i) {
        const PointF& a = hull[i];
        const PointF& b = hull[(i + 1) % hull.size()];
        float ux = b.x - a.x;
        float uy = b.y - a.y;
        const float len = std::sqrt(ux * ux + uy * uy);
        if (len <= 1e-6f) continue;
        ux /= len;
        uy /= len;
        const float vx = -uy;
        const float vy = ux;

        float minU = std::numeric_limits<float>::max();
        float maxU = -std::numeric_limits<float>::max();
        float minV = minU;
        float maxV = maxU;
        for (const PointF& point : hull) {
            const float pu = point.x * ux + point.y * uy;
            const float pv = point.x * vx + point.y * vy;
            minU = std::min(minU, pu);
            maxU = std::max(maxU, pu);
            minV = std::min(minV, pv);
            maxV = std::max(maxV, pv);
        }

        const float area = (maxU - minU) * (maxV - minV);
        if (area < bestArea) {
            bestArea = area;
            const float cu = (minU + maxU) * 0.5f;
            const float cv = (minV + maxV) * 0.5f;
            rect.center = PointF{cu * ux + cv * vx, cu * uy + cv * vy};
            rect.axisU = PointF{ux, uy};
            rect.axisV = PointF{vx, vy};
            rect.width = maxU - minU;
            rect.height = maxV - minV;
        }
    }
    return rect;
}

void rectCorners(const MinAreaRect& rect, PointF out[4]) {
    const float hw = rect.width * 0.5f;
    const float hh = rect.height * 0.5f;
    const PointF& u = rect.axisU;
    const PointF& v = rect.axisV;
    out[0] = PointF{rect.center.x - u.x * hw - v.x * hh, rect.center.y - u.y * hw - v.y * hh};
    out[1] = PointF{rect.center.x + u.x * hw - v.x * hh, rect.center.y + u.y * hw - v.y * hh};
    out[2] = PointF{rect.center.x + u.x * hw + v.x * hh, rect.center.y + u.y * hw + v.y * hh};
    out[3] = PointF{rect.center.x - u.x * hw + v.x * hh, rect.center.y - u.y * hw + v.y * hh};
}

}  // namespace ocr
