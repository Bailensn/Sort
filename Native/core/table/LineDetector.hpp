#pragma once

#include <vector>

#include "ocr/Types.hpp"
#include "table/GridBuilder.hpp"

namespace ocr {

/// 拟合出的一条线：y = slope * t + intercept。
struct FittedLine {
    float slope = 0;
    float intercept = 0;
    float valueAt(float t) const { return slope * t + intercept; }
};

struct LineDetection {
    int width = 0;
    int height = 0;
    std::vector<FittedLine> horizontal;
    std::vector<FittedLine> vertical;

    bool usable() const { return horizontal.size() >= 2 && vertical.size() >= 2; }
    GridLines toGridLines() const;
};

/// 从整张图里找横竖线：灰度 → 压平光照 → 自适应阈值 → 形态学开运算 → 分带拟合。
LineDetection detectLines(const Image8& image);

}  // namespace ocr
