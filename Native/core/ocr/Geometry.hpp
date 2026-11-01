#pragma once

#include <vector>

#include "Types.hpp"

namespace ocr {

/// 最小外接矩形：中心 + 两条正交轴 + 宽高，保留朝向，斜着的字不会被摆正。
struct MinAreaRect {
    PointF center;
    PointF axisU{1.0f, 0.0f};
    PointF axisV{0.0f, 1.0f};
    float width = 0.0f;
    float height = 0.0f;
};

std::vector<PointF> convexHull(std::vector<PointF> points);

MinAreaRect minAreaRect(const std::vector<PointF>& points);

/// 按矩形自己的轴展开四角：中心 ± 半宽 × axisU ± 半高 × axisV。
void rectCorners(const MinAreaRect& rect, PointF out[4]);

}  // namespace ocr
