#include "table/TableAnalyzer.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

#include "table/ColumnCleaner.hpp"
#include "table/LineDetector.hpp"
#include "table/Perspective.hpp"

namespace ocr {
namespace {

struct CellRect {
    Rect rect;
    size_t row = 0;
    size_t column = 0;
};

/// 带着行列号返回：后面按号回填，跳过某个退化格子也不会整排错位。
std::vector<CellRect> cellRects(const GridLines& lines, int width, int height) {
    std::vector<CellRect> cells;
    std::vector<float> rows = lines.horizontal;
    std::vector<float> cols = lines.vertical;
    std::sort(rows.begin(), rows.end());
    std::sort(cols.begin(), cols.end());
    for (size_t r = 0; r + 1 < rows.size(); ++r) {
        for (size_t c = 0; c + 1 < cols.size(); ++c) {
            Rect rect;
            rect.left = std::max(0, static_cast<int>(std::lround(cols[c])));
            rect.top = std::max(0, static_cast<int>(std::lround(rows[r])));
            rect.right = std::min(width, static_cast<int>(std::lround(cols[c + 1])));
            rect.bottom = std::min(height, static_cast<int>(std::lround(rows[r + 1])));
            if (rect.right <= rect.left || rect.bottom <= rect.top) continue;
            cells.push_back(CellRect{rect, r, c});
        }
    }
    return cells;
}

}  // namespace

TableAnalysis analyzeTable(OcrEngine& engine, const Image8& image) {
    TableAnalysis analysis;
    if (image.width <= 0 || image.height <= 0) return analysis;

    const LineDetection detection = detectLines(image);
    analysis.horizontalLines = static_cast<int>(detection.horizontal.size());
    analysis.verticalLines = static_cast<int>(detection.vertical.size());

    TableRows rows;
    bool byLines = false;
    if (detection.usable()) {
        const GridLines lines = detection.toGridLines();
        const std::vector<CellRect> cells = cellRects(lines, image.width, image.height);
        std::vector<Rect> rects;
        rects.reserve(cells.size());
        for (const auto& cell : cells) rects.push_back(cell.rect);
        const std::vector<CellReading> readings = engine.recognizeCells(image, rects);
        // 线检测给出的是格子的拓扑，尺寸按线数还原
        const size_t lineCount = lines.horizontal.size();
        const size_t columnCount = lines.vertical.size();
        if (lineCount >= 2 && columnCount >= 2) {
            const size_t rowCells = columnCount - 1;
            rows.assign(lineCount - 1, std::vector<std::string>(rowCells));
            for (size_t i = 0; i < readings.size() && i < cells.size(); ++i) {
                rows[cells[i].row][cells[i].column] = readings[i].text;
            }
            byLines = true;
        }
    }

    if (rows.empty()) {
        const OcrResult result = engine.recognize(image);
        std::vector<TextRegion> regions;
        regions.reserve(result.items.size());
        for (const auto& item : result.items) {
            float left = item.box.points[0].x;
            float right = item.box.points[0].x;
            float top = item.box.points[0].y;
            float bottom = item.box.points[0].y;
            for (int i = 1; i < 4; ++i) {
                left = std::min(left, item.box.points[i].x);
                right = std::max(right, item.box.points[i].x);
                top = std::min(top, item.box.points[i].y);
                bottom = std::max(bottom, item.box.points[i].y);
            }
            regions.push_back(TextRegion{left, top, right, bottom, item.text});
        }
        if (const auto grid = buildFromClusters(regions)) {
            rows = grid->rows;
            byLines = false;
        }
    }

    if (rows.empty()) return analysis;

    TableRows cleaned = cleanNoisyColumns(cleanBadColumns(rows));
    const TrimmedColumns trimmed = trimColumns(cleaned);
    analysis.header = detectHeader(trimmed.rows);
    const TableRows body = analysis.header.fromImage
                               ? TableRows(trimmed.rows.begin() + 1, trimmed.rows.end())
                               : trimmed.rows;
    const CheckResult checked = selfCheck(body, nullptr, &analysis.header);
    analysis.report = checked.report;

    if (analysis.header.fromImage && !trimmed.rows.empty()) {
        analysis.rows.push_back(trimmed.rows.front());
    }
    for (const auto& row : checked.rows) analysis.rows.push_back(row);
    analysis.byLines = byLines;
    return analysis;
}

}  // namespace ocr
