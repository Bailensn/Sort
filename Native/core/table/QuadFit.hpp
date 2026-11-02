#pragma once

#include <optional>

#include "ocr/Types.hpp"
#include "table/LineDetector.hpp"

namespace ocr {

struct TableQuad {
    /// 左上、右上、右下、左下，单位是原图像素。
    PointF corners[4];
};

/// 由最外圈四条线求表格外框，并按内容边界扩展到漏检的那条边。
/// 求交退化、角度太歪、尺寸离谱时返回空。
std::optional<TableQuad> fitTableQuad(const Image8& src, const LineDetection& detection);

}  // namespace ocr
