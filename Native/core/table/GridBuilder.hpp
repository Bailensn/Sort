#pragma once

#include <string>
#include <vector>

#include "table/TableLogic.hpp"

namespace ocr {

/// OCR 文本框的轴对齐包围盒，单位是原图像素。
struct TextRegion {
    float left = 0;
    float top = 0;
    float right = 0;
    float bottom = 0;
    std::string text;

    float centerX() const { return (left + right) / 2; }
    float centerY() const { return (top + bottom) / 2; }
    float width() const { return right - left; }
    float height() const { return bottom - top; }
};

struct GridLines {
    std::vector<float> horizontal;
    std::vector<float> vertical;

    bool usable() const { return horizontal.size() >= 2 && vertical.size() >= 2; }
};

struct TableGrid {
    TableRows rows;
    /// true = 靠检测到的横竖线切格，false = 没有线，按文字间距聚类。
    bool byLines = true;
};

/// 有线时按线切格；线不可用或没有文字返回空。
std::optional<TableGrid> buildFromLines(const std::vector<TextRegion>& regions,
                                        const GridLines& lines);

/// 没有线时按文字间距聚类。
std::optional<TableGrid> buildFromClusters(const std::vector<TextRegion>& regions);

/// 统一收尾：补宽、去掉开头多余空行（保留至多 2 行）、去掉尾部全空列。
std::optional<TableGrid> normalizeCells(const TableRows& cells, bool byLines);

}  // namespace ocr
