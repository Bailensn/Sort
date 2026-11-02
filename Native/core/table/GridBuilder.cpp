#include "table/GridBuilder.hpp"

#include <algorithm>
#include <cmath>
#include <map>
#include <numeric>

namespace ocr {
namespace {

constexpr int kMaxLeadingBlankRows = 2;

std::string trim(const std::string& value) {
    size_t begin = 0;
    size_t end = value.size();
    while (begin < end && std::isspace(static_cast<unsigned char>(value[begin]))) ++begin;
    while (end > begin && std::isspace(static_cast<unsigned char>(value[end - 1]))) --end;
    return value.substr(begin, end - begin);
}

bool blank(const std::string& value) { return trim(value).empty(); }

std::string joinCell(const std::vector<const TextRegion*>& regions) {
    std::vector<const TextRegion*> sorted = regions;
    std::sort(sorted.begin(), sorted.end(), [](const TextRegion* a, const TextRegion* b) {
        if (a->top != b->top) return a->top < b->top;
        return a->left < b->left;
    });
    std::string out;
    for (const auto* region : sorted) {
        if (!out.empty()) out += '\n';
        out += trim(region->text);
    }
    return trim(out);
}

}  // namespace

std::optional<TableGrid> buildFromLines(const std::vector<TextRegion>& regions,
                                        const GridLines& lines) {
    if (!lines.usable() || regions.empty()) return std::nullopt;

    std::vector<float> rowBounds = lines.horizontal;
    std::vector<float> columnBounds = lines.vertical;
    std::sort(rowBounds.begin(), rowBounds.end());
    std::sort(columnBounds.begin(), columnBounds.end());

    const int rowCount = static_cast<int>(rowBounds.size()) - 1;
    const int columnCount = static_cast<int>(columnBounds.size()) - 1;
    if (rowCount <= 0 || columnCount <= 0) return std::nullopt;

    std::vector<std::vector<std::vector<const TextRegion*>>> buckets(
        static_cast<size_t>(rowCount), std::vector<std::vector<const TextRegion*>>(
                                           static_cast<size_t>(columnCount)));

    for (const auto& region : regions) {
        int row = -1;
        for (int i = 0; i < static_cast<int>(rowBounds.size()); ++i) {
            if (rowBounds[static_cast<size_t>(i)] <= region.centerY()) row = i;
        }
        int column = -1;
        for (int i = 0; i < static_cast<int>(columnBounds.size()); ++i) {
            if (columnBounds[static_cast<size_t>(i)] <= region.centerX()) column = i;
        }
        if (row < 0 || row >= rowCount || column < 0 || column >= columnCount) continue;
        buckets[static_cast<size_t>(row)][static_cast<size_t>(column)].push_back(&region);
    }

    TableRows cells;
    cells.reserve(static_cast<size_t>(rowCount));
    for (const auto& row : buckets) {
        std::vector<std::string> line;
        line.reserve(static_cast<size_t>(columnCount));
        for (const auto& bucket : row) line.push_back(joinCell(bucket));
        cells.push_back(std::move(line));
    }
    return normalizeCells(cells, true);
}

std::optional<TableGrid> buildFromClusters(const std::vector<TextRegion>& regions) {
    if (regions.empty()) return std::nullopt;

    std::vector<float> heights;
    heights.reserve(regions.size());
    for (const auto& region : regions) heights.push_back(region.height());
    std::sort(heights.begin(), heights.end());
    const float medianHeight = heights[heights.size() / 2];
    const float rowGap = std::max(medianHeight * 0.6f, 4.0f);

    std::vector<const TextRegion*> sorted;
    sorted.reserve(regions.size());
    for (const auto& region : regions) sorted.push_back(&region);
    std::sort(sorted.begin(), sorted.end(),
              [](const TextRegion* a, const TextRegion* b) { return a->centerY() < b->centerY(); });

    std::vector<std::vector<const TextRegion*>> rowGroups;
    for (const auto* region : sorted) {
        if (!rowGroups.empty()) {
            auto& group = rowGroups.back();
            float sum = 0;
            for (const auto* item : group) sum += item->centerY();
            const float center = sum / static_cast<float>(group.size());
            if (region->centerY() - center <= rowGap) {
                group.push_back(region);
                continue;
            }
        }
        rowGroups.push_back({region});
    }

    float tableLeft = regions.front().left;
    float tableRight = regions.front().right;
    for (const auto& region : regions) {
        tableLeft = std::min(tableLeft, region.left);
        tableRight = std::max(tableRight, region.right);
    }
    const float tableWidth = std::max(tableRight - tableLeft, 1.0f);
    const float minGap = std::max(tableWidth * 0.01f, 8.0f);

    // 特别宽的行（标题、说明）会把所有列桥接成一列，算列时先排除
    std::vector<const TextRegion*> samples;
    for (const auto& region : regions) {
        if (region.width() < tableWidth * 0.5f) samples.push_back(&region);
    }
    if (samples.empty()) {
        for (const auto& region : regions) samples.push_back(&region);
    }

    const int bins = std::max(static_cast<int>(tableWidth), 1) + 1;
    std::vector<bool> covered(static_cast<size_t>(bins), false);
    for (const auto* region : samples) {
        int start = static_cast<int>(region->left - tableLeft);
        int end = static_cast<int>(region->right - tableLeft);
        start = std::clamp(start, 0, bins - 1);
        end = std::clamp(end, 0, bins - 1);
        for (int index = start; index <= end; ++index) covered[static_cast<size_t>(index)] = true;
    }

    std::vector<std::pair<int, int>> runs;
    int runStart = -1;
    for (int index = 0; index <= bins; ++index) {
        const bool hit = index < bins && covered[static_cast<size_t>(index)];
        if (hit && runStart < 0) runStart = index;
        if (!hit && runStart >= 0) {
            runs.emplace_back(runStart, index - 1);
            runStart = -1;
        }
    }
    if (runs.empty()) return std::nullopt;

    std::vector<std::pair<int, int>> merged;
    std::pair<int, int> current = runs.front();
    for (size_t i = 1; i < runs.size(); ++i) {
        if (runs[i].first - current.second <= static_cast<int>(minGap)) {
            current.second = runs[i].second;
        } else {
            merged.push_back(current);
            current = runs[i];
        }
    }
    merged.push_back(current);

    if (merged.empty()) return std::nullopt;

    TableRows cells;
    cells.reserve(rowGroups.size());
    for (const auto& group : rowGroups) {
        std::vector<std::string> row(merged.size());
        std::vector<const TextRegion*> ordered = group;
        std::sort(ordered.begin(), ordered.end(),
                  [](const TextRegion* a, const TextRegion* b) { return a->left < b->left; });
        for (const auto* region : ordered) {
            int index = -1;
            for (size_t i = 0; i < merged.size(); ++i) {
                if (static_cast<float>(merged[i].first) + tableLeft <= region->centerX()) {
                    index = static_cast<int>(i);
                }
            }
            if (index < 0 || index >= static_cast<int>(row.size())) continue;
            std::string& slot = row[static_cast<size_t>(index)];
            const std::string text = trim(region->text);
            slot = slot.empty() ? text : slot + "\n" + text;
        }
        cells.push_back(std::move(row));
    }
    return normalizeCells(cells, false);
}

std::optional<TableGrid> normalizeCells(const TableRows& cells, bool byLines) {
    if (cells.empty()) return std::nullopt;
    size_t width = 0;
    for (const auto& row : cells) width = std::max(width, row.size());
    if (width == 0) return std::nullopt;

    TableRows padded;
    padded.reserve(cells.size());
    for (const auto& row : cells) {
        std::vector<std::string> line = row;
        line.resize(width);
        padded.push_back(std::move(line));
    }

    int leading = -1;
    for (size_t i = 0; i < padded.size(); ++i) {
        bool any = false;
        for (const auto& cell : padded[i]) {
            if (!blank(cell)) any = true;
        }
        if (any) {
            leading = static_cast<int>(i);
            break;
        }
    }
    if (leading < 0) return std::nullopt;

    // 开头留最多 2 行空白：最上面那行常被画面边缘切掉，全丢会让整表错位
    const size_t keepFrom = static_cast<size_t>(std::max(0, leading - kMaxLeadingBlankRows));
    TableRows trimmed(padded.begin() + static_cast<long>(keepFrom), padded.end());
    while (!trimmed.empty()) {
        bool allBlank = true;
        for (const auto& cell : trimmed.back()) {
            if (!blank(cell)) allBlank = false;
        }
        if (!allBlank) break;
        trimmed.pop_back();
    }
    if (trimmed.empty()) return std::nullopt;

    int lastColumn = static_cast<int>(width) - 1;
    while (lastColumn >= 0) {
        bool allBlank = true;
        for (const auto& row : trimmed) {
            if (!blank(row[static_cast<size_t>(lastColumn)])) allBlank = false;
        }
        if (!allBlank) break;
        --lastColumn;
    }
    if (lastColumn < 0) return std::nullopt;

    TableGrid grid;
    grid.byLines = byLines;
    for (auto& row : trimmed) row.resize(static_cast<size_t>(lastColumn) + 1);
    grid.rows = std::move(trimmed);
    return grid;
}

}  // namespace ocr
