#pragma once

#include <string>
#include <vector>

#include "ocr/OcrEngine.hpp"
#include "ocr/Types.hpp"
#include "table/GridBuilder.hpp"
#include "table/TableLogic.hpp"

namespace ocr {

struct TableAnalysis {
    TableRows rows;
    TableHeader header;
    Report report;
    bool byLines = true;
    int horizontalLines = 0;
    int verticalLines = 0;
};

/// 一整条表格流水线：找线 → 切格/聚类 → 逐格识别 → 清洗 → 认表头 → 算术自检。
TableAnalysis analyzeTable(OcrEngine& engine, const Image8& image);

}  // namespace ocr
