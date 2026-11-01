#pragma once

#include <cstdint>
#include <vector>

namespace ocr {

struct PointF {
    float x = 0.0f;
    float y = 0.0f;
};

struct Rect {
    int left = 0;
    int top = 0;
    int right = 0;
    int bottom = 0;

    int width() const { return right - left; }
    int height() const { return bottom - top; }
};

enum class ColorOrder { BGR, RGB };

/// 8 位三通道位图，行优先、无对齐填充。
struct Image8 {
    int width = 0;
    int height = 0;
    ColorOrder order = ColorOrder::BGR;
    std::vector<uint8_t> data;

    bool empty() const { return width <= 0 || height <= 0 || data.empty(); }
    size_t stride() const { return static_cast<size_t>(width) * 3; }

    uint8_t* row(int y) { return data.data() + static_cast<size_t>(y) * stride(); }
    const uint8_t* row(int y) const { return data.data() + static_cast<size_t>(y) * stride(); }
};

/// 四点框，顺序固定为左上、右上、右下、左下。
struct Box {
    PointF points[4];
};

/// 行优先浮点张量，形状由 dims 描述。
struct Tensor {
    std::vector<int64_t> dims;
    std::vector<float> data;
};

}  // namespace ocr
