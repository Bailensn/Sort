#pragma once

#include <functional>
#include <string>
#include <vector>

#include "ocr/OcrEngine.hpp"
#include "ocr/Types.hpp"
#include "table/GridBuilder.hpp"
#include "table/TableLogic.hpp"

namespace ocr {

struct TableStats {
    bool byLines = true;
    bool rectified = false;
    int horizontalLines = 0;
    int verticalLines = 0;
    int cellCount = 0;
    int correctedCells = 0;
    int lowConfidenceCells = 0;
    float averageConfidence = 0.0f;
    float confidenceMin = 0.0f;
    float confidenceMax = 0.0f;
    double deskewDegrees = 0.0;
    double elapsedMs = 0.0;
    double cellMs = 0.0;
    double detectMs = 0.0;
};

struct TableAnalysis {
    /// 裁切、转正、透视校正之后的工作图；界面直接显示它。
    Image8 working;
    /// 工作图上叠了表格线，和 working 同尺寸。
    Image8 overlay;
    /// 压平光照 + 拉对比度后的灰度图（三通道），历史里存的那张「增强图」。
    Image8 enhanced;
    TableRows rows;
    TableHeader header;
    Report report;
    TableStats stats;
};

/// 流水线阶段，界面按它换文案。
enum class TableStage { Prepare, Segment, Recognize, Verify };

/// fraction 是 0..1 的完成度，只在同一线程同步回调。
using TableProgress = std::function<void(TableStage stage, float fraction)>;

/// 一整条表格流水线：裁内容 → 纠偏 → 找线 → 透视校正 → 多遍识别投票 → 清洗 → 表头 → 自检。
TableAnalysis analyzeTable(OcrEngine& engine, const Image8& image,
                           const TableProgress& progress = TableProgress{});

}  // namespace ocr
