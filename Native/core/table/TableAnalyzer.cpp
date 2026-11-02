#include "table/TableAnalyzer.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <unordered_map>
#include <utility>
#include <vector>

#include "imgproc/Enhance.hpp"
#include "imgproc/Gray.hpp"
#include "imgproc/Threshold.hpp"
#include "imgproc/Warp.hpp"
#include "ocr/Postprocess.hpp"
#include "table/ColumnCleaner.hpp"
#include "table/Cropper.hpp"
#include "table/EdgeLines.hpp"
#include "table/LineDetector.hpp"
#include "table/LineWidth.hpp"
#include "table/Overlay.hpp"
#include "table/Perspective.hpp"
#include "table/QuadFit.hpp"
#include "table/Skew.hpp"

namespace ocr {
namespace {

using Clock = std::chrono::steady_clock;

double elapsedMs(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

/// 低于这个值才值得再认一遍；1.0 等于「二遍全跑」，时间翻三倍换一堆同样的结论。
constexpr float kTrustedConfidence = 0.95f;
constexpr float kThirdConfidence = 0.98f;
constexpr float kHopelessConfidence = 0.6f;
constexpr float kMinFillConfidence = 0.6f;
constexpr float kDisplayTrustedConfidence = 0.98f;
constexpr float kStrictScale = 2.0f;
constexpr float kThirdScale = 3.0f;
constexpr float kRefereeScale = 2.0f;
constexpr float kVoteBonus = 0.12f;
constexpr float kMinCellTextConfidence = 0.25f;
constexpr float kDetectorOverlap = 0.75f;
constexpr int kMinCellGap = 6;

struct CellRect {
    Rect rect;
    size_t row = 0;
    size_t column = 0;
};

struct CellLayout {
    std::vector<CellRect> cells;
    size_t rows = 0;
    size_t columns = 0;
};

struct PassOutput {
    std::vector<CellReading> readings;
    std::unordered_map<size_t, CellReading> byCell;
    double cellMs = 0.0;
    double detectMs = 0.0;
};

std::string trimText(const std::string& value) {
    size_t begin = 0;
    size_t end = value.size();
    while (begin < end && std::isspace(static_cast<unsigned char>(value[begin]))) ++begin;
    while (end > begin && std::isspace(static_cast<unsigned char>(value[end - 1]))) --end;
    return value.substr(begin, end - begin);
}

bool contains(const std::string& text, const std::string& needle) {
    return text.find(needle) != std::string::npos;
}

std::vector<int> snapLines(const std::vector<float>& values, int limit) {
    std::vector<int> sorted;
    sorted.reserve(values.size());
    for (float value : values) {
        sorted.push_back(std::clamp(static_cast<int>(std::lround(value)), 0, limit));
    }
    std::sort(sorted.begin(), sorted.end());

    std::vector<int> out;
    for (int value : sorted) {
        if (out.empty() || value - out.back() >= kMinCellGap) out.push_back(value);
    }
    return out;
}

CellLayout buildCells(const GridLines& lines, int width, int height) {
    CellLayout layout;
    if (!lines.usable()) return layout;

    const std::vector<int> rowBounds = snapLines(lines.horizontal, height);
    const std::vector<int> columnBounds = snapLines(lines.vertical, width);
    if (rowBounds.size() < 2 || columnBounds.size() < 2) return layout;

    layout.rows = rowBounds.size() - 1;
    layout.columns = columnBounds.size() - 1;
    for (size_t row = 0; row < layout.rows; ++row) {
        for (size_t column = 0; column < layout.columns; ++column) {
            Rect rect;
            rect.left = columnBounds[column];
            rect.top = rowBounds[row];
            rect.right = columnBounds[column + 1];
            rect.bottom = rowBounds[row + 1];
            if (rect.right <= rect.left || rect.bottom <= rect.top) continue;
            layout.cells.push_back(CellRect{rect, row, column});
        }
    }
    return layout;
}

int luminanceAt(const Image8& src, int x, int y) {
    const uint8_t* pixel = src.row(y) + static_cast<size_t>(x) * 3u;
    const bool bgr = src.order == ColorOrder::BGR;
    const int r = bgr ? pixel[2] : pixel[0];
    const int g = pixel[1];
    const int b = bgr ? pixel[0] : pixel[2];
    return (r * 299 + g * 587 + b * 114) / 1000;
}

/// 抽点估暗像素占比；用来判断 warp 之后是不是白成一片。
double darkRatio(const Image8& src, int samples) {
    if (src.empty() || samples <= 0) return 0.0;
    int dark = 0;
    int total = 0;
    for (int iy = 0; iy < samples; ++iy) {
        const int y = std::min(src.height - 1, iy * src.height / samples);
        for (int ix = 0; ix < samples; ++ix) {
            const int x = std::min(src.width - 1, ix * src.width / samples);
            if (luminanceAt(src, x, y) < 160) ++dark;
            ++total;
        }
    }
    return total > 0 ? static_cast<double>(dark) / static_cast<double>(total) : 0.0;
}

bool looksBlank(const Image8& src) { return darkRatio(src, 64) < 0.002; }

bool keepsInk(const Image8& before, const Image8& after) {
    const double base = darkRatio(before, 96);
    if (base <= 0.0) return true;
    return darkRatio(after, 96) >= base * 0.5;
}

float overlapArea(const OcrItem& item, const Rect& cell) {
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
    const float x0 = std::max(left, static_cast<float>(cell.left));
    const float y0 = std::max(top, static_cast<float>(cell.top));
    const float x1 = std::min(right, static_cast<float>(cell.right));
    const float y1 = std::min(bottom, static_cast<float>(cell.bottom));
    if (x1 <= x0 || y1 <= y0) return 0.0f;
    return (x1 - x0) * (y1 - y0);
}

float boxArea(const OcrItem& item) {
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
    return (right - left) * (bottom - top);
}

/// 多遍投票：认成同一串字的两路互相加成，独立两次都一致基本就是对的。
CellReading pickBest(const std::vector<CellReading>& candidates) {
    if (candidates.empty()) return CellReading{};
    if (candidates.size() == 1) return candidates.front();

    const CellReading* best = nullptr;
    float bestScore = -1.0f;
    for (const auto& candidate : candidates) {
        if (trimText(candidate.text).empty()) continue;
        int votes = 0;
        float top = 0.0f;
        for (const auto& other : candidates) {
            if (other.text != candidate.text) continue;
            ++votes;
            top = std::max(top, other.confidence);
        }
        const float score = std::min(1.0f, top + kVoteBonus * static_cast<float>(votes - 1));
        if (score > bestScore) {
            bestScore = score;
            best = &candidate;
        }
    }
    if (best != nullptr) return *best;

    const CellReading* fallback = &candidates.front();
    for (const auto& candidate : candidates) {
        if (candidate.confidence > fallback->confidence) fallback = &candidate;
    }
    return *fallback;
}

std::vector<Rect> insetRects(const std::vector<CellRect>& cells, int borderInset) {
    std::vector<Rect> rects;
    rects.reserve(cells.size());
    for (const auto& cell : cells) {
        // 按线宽往内缩，把表格线挡在识别图之外；小格子再额外缩一点
        const int inset = std::max(borderInset, std::min(cell.rect.width(), cell.rect.height()) / 40);
        Rect rect;
        rect.left = std::min(cell.rect.left + inset, cell.rect.right - 1);
        rect.top = std::min(cell.rect.top + inset, cell.rect.bottom - 1);
        rect.right = std::max(cell.rect.right - inset, cell.rect.left + 1);
        rect.bottom = std::max(cell.rect.bottom - inset, cell.rect.top + 1);
        rects.push_back(rect);
    }
    return rects;
}

PassOutput recognizeDeep(OcrEngine& engine, const Image8& work, const Image8& enhanced,
                         const std::vector<Rect>& rects) {
    PassOutput pass;
    const auto cellStart = Clock::now();

    pass.readings = engine.recognizeCells(work, rects);

    // 二遍：增强图 2 倍。主模型 + 复核模型一起上，加上第一遍共三路投票。
    std::vector<size_t> strict;
    for (size_t i = 0; i < pass.readings.size(); ++i) {
        const CellReading& reading = pass.readings[i];
        if (reading.hasInk && reading.confidence < kTrustedConfidence) strict.push_back(i);
    }
    if (!strict.empty() && !enhanced.empty()) {
        std::vector<Rect> strictRects;
        strictRects.reserve(strict.size());
        for (size_t index : strict) strictRects.push_back(rects[index]);
        const std::vector<CellReading> second = engine.recognizeCells(enhanced, strictRects, kStrictScale);

        // 复核模型只跑有争议的格子：认得完全一致的多一路投票不会改变结果
        std::vector<size_t> positions;
        for (size_t position = 0; position < strict.size(); ++position) {
            const CellReading& current = pass.readings[strict[position]];
            const CellReading candidate = position < second.size() ? second[position] : CellReading{};
            if (candidate.text != current.text ||
                std::max(candidate.confidence, current.confidence) < kThirdConfidence) {
                positions.push_back(position);
            }
        }

        std::unordered_map<size_t, CellReading> referee;
        if (!positions.empty() && engine.hasReferee()) {
            std::vector<Rect> refereeRects;
            refereeRects.reserve(positions.size());
            for (size_t position : positions) refereeRects.push_back(strictRects[position]);
            const std::vector<CellReading> readings =
                engine.recognizeCells(enhanced, refereeRects, kRefereeScale, true);
            for (size_t offset = 0; offset < positions.size() && offset < readings.size(); ++offset) {
                referee[positions[offset]] = readings[offset];
            }
        }

        for (size_t position = 0; position < strict.size(); ++position) {
            std::vector<CellReading> candidates;
            candidates.push_back(pass.readings[strict[position]]);
            if (position < second.size()) candidates.push_back(second[position]);
            const auto found = referee.find(position);
            if (found != referee.end()) candidates.push_back(found->second);
            if (candidates.size() > 1) pass.readings[strict[position]] = pickBest(candidates);
        }
    }

    // 三遍：还不到 98% 的格子换成原图 3 倍 + 复核模型，保证三路来自不同的输入形态。
    std::vector<size_t> third;
    for (size_t i = 0; i < pass.readings.size(); ++i) {
        const CellReading& reading = pass.readings[i];
        if (reading.hasInk && reading.confidence < kThirdConfidence) third.push_back(i);
    }
    if (!third.empty()) {
        std::vector<Rect> thirdRects;
        thirdRects.reserve(third.size());
        for (size_t index : third) thirdRects.push_back(rects[index]);

        const std::vector<CellReading> fromOriginal = engine.recognizeCells(work, thirdRects, kThirdScale);
        std::vector<CellReading> fromReferee;
        if (engine.hasReferee() && !enhanced.empty()) {
            const std::vector<CellReading> readings =
                engine.recognizeCells(enhanced, thirdRects, kRefereeScale, true);
            if (readings.size() == thirdRects.size()) fromReferee = readings;
        }

        for (size_t position = 0; position < third.size(); ++position) {
            std::vector<CellReading> candidates;
            candidates.push_back(pass.readings[third[position]]);
            if (position < fromOriginal.size()) candidates.push_back(fromOriginal[position]);
            if (position < fromReferee.size()) candidates.push_back(fromReferee[position]);
            pass.readings[third[position]] = pickBest(candidates);
        }
    }

    // 四遍：低到看不清的格子用二值图再认一次，输入形态和灰度图完全不同。
    std::vector<size_t> hopeless;
    for (size_t i = 0; i < pass.readings.size(); ++i) {
        const CellReading& reading = pass.readings[i];
        if (reading.hasInk && reading.confidence < kHopelessConfidence) hopeless.push_back(i);
    }
    if (!hopeless.empty()) {
        std::vector<uint8_t> binaryGray = toGray(work);
        if (!binaryGray.empty()) {
            flattenLighting(binaryGray, work.width, work.height);
            const std::vector<uint8_t> binary = thresholdBinary(binaryGray, otsuThreshold(binaryGray));
            const Image8 binaryImage = grayToImage(binary, work.width, work.height);
            std::vector<Rect> hopelessRects;
            hopelessRects.reserve(hopeless.size());
            for (size_t index : hopeless) hopelessRects.push_back(rects[index]);
            const std::vector<CellReading> fromBinary =
                engine.recognizeCells(binaryImage, hopelessRects, kStrictScale);
            for (size_t position = 0; position < hopeless.size() && position < fromBinary.size();
                 ++position) {
                std::vector<CellReading> candidates;
                candidates.push_back(pass.readings[hopeless[position]]);
                candidates.push_back(fromBinary[position]);
                pass.readings[hopeless[position]] = pickBest(candidates);
            }
        }
    }

    pass.cellMs = elapsedMs(cellStart);

    // 回读：整图再检测一遍，用它的结果和逐格结果互相校对。
    const auto detectStart = Clock::now();
    const OcrResult result = engine.recognize(work);
    pass.detectMs = elapsedMs(detectStart);

    for (const auto& item : result.items) {
        const float area = boxArea(item);
        if (area <= 4.0f) continue;

        size_t bestIndex = 0;
        float bestOverlap = 0.0f;
        bool found = false;
        for (size_t index = 0; index < rects.size(); ++index) {
            const float overlap = overlapArea(item, rects[index]);
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                bestIndex = index;
                found = true;
            }
        }
        // 跨列框（姓名+分数粘一起）直接丢，只有几乎完整落在一个格子里才采信
        if (!found || bestOverlap / area < kDetectorOverlap) continue;
        if (looksLikeLine(item.text)) continue;

        const std::string text = cleanCellText(item.text);
        if (text.empty()) continue;
        const auto previous = pass.byCell.find(bestIndex);
        if (previous == pass.byCell.end() || item.confidence > previous->second.confidence) {
            pass.byCell[bestIndex] = CellReading{text, item.confidence, true};
        }
    }
    return pass;
}

bool gridFromClusters(OcrEngine& engine, const Image8& work, TableRows& rows) {
    const OcrResult result = engine.recognize(work);
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
    const auto grid = buildFromClusters(regions);
    if (!grid) return false;
    rows = grid->rows;
    return true;
}

}  // namespace

TableAnalysis analyzeTable(OcrEngine& engine, const Image8& image) {
    const auto startAll = Clock::now();
    TableAnalysis analysis;
    if (image.empty()) return analysis;

    // 第一步就把画面裁到有内容的那块：桌面、黑边、灰底不会带偏后面的检测
    Image8 work = image;
    if (const auto bounds = contentBounds(image)) {
        Image8 cropped = cropImage(image, *bounds);
        if (!cropped.empty()) work = std::move(cropped);
    }

    const SkewResult skew = deskewImage(work);
    if (!skew.image.empty()) work = skew.image;
    analysis.stats.deskewDegrees = skew.degrees;

    LineDetection detection = detectLines(work);
    if (detection.usable()) {
        if (const auto quad = fitTableQuad(work, detection)) {
            const Image8 warped = rectifyPerspective(work, quad->corners);
            // 尺寸没变说明 warp 内部判定退化，原样退回了输入
            const bool resized = warped.width != work.width || warped.height != work.height;
            if (resized) {
                const LineDetection warpedDetection = detectLines(warped);
                if (!looksBlank(warped) && keepsInk(work, warped) && warpedDetection.usable()) {
                    work = warped;
                    detection = warpedDetection;
                    analysis.stats.rectified = true;
                }
            }
        }
    }

    // 二遍识别的输入：压平光照 + 拉对比度。CLIP 比线检测那档更强，这里要的是看清字。
    std::vector<uint8_t> enhancedGray = toGray(work);
    Image8 enhanced;
    if (!enhancedGray.empty()) {
        flattenLighting(enhancedGray, work.width, work.height);
        clahe(enhancedGray, work.width, work.height, 3.0, 8);
        enhanced = grayToImage(enhancedGray, work.width, work.height);
    }

    detection = extendEdgeLines(work, detection);
    analysis.stats.horizontalLines = static_cast<int>(detection.horizontal.size());
    analysis.stats.verticalLines = static_cast<int>(detection.vertical.size());

    const GridLines lines = detection.toGridLines();
    const CellLayout layout = buildCells(lines, work.width, work.height);
    analysis.stats.cellCount = static_cast<int>(layout.cells.size());

    TableRows rows;
    TableConfidence alignedConfidence;
    int corrected = 0;

    if (!layout.cells.empty()) {
        const int borderInset = estimateLineWidth(work, lines) + 1;
        const std::vector<Rect> rects = insetRects(layout.cells, borderInset);
        const PassOutput pass = recognizeDeep(engine, work, enhanced, rects);

        TableRows matrix(layout.rows, std::vector<std::string>(layout.columns));
        TableConfidence confidenceGrid(layout.rows, std::vector<float>(layout.columns, 0.0f));

        for (size_t index = 0; index < layout.cells.size(); ++index) {
            const CellRect& cell = layout.cells[index];
            const CellReading reading =
                index < pass.readings.size() ? pass.readings[index] : CellReading{};
            const std::string cellText =
                reading.confidence >= kMinCellTextConfidence ? trimText(reading.text) : std::string();

            const auto detector = pass.byCell.find(index);
            std::string chosen;
            if (detector == pass.byCell.end()) {
                // 整图检测没落到这个格子里，以逐格结果为准
                chosen = cellText;
            } else if (!reading.hasInk) {
                // 格子里没墨迹 = 真空白格：用检测去补会把邻格文字填进来
                chosen = cellText;
            } else if (cellText.empty()) {
                // 逐格没认出来时宁可留空：空白格被填上 1 比漏一格更难查
                if (detector->second.confidence >= kMinFillConfidence) {
                    ++corrected;
                    chosen = detector->second.text;
                }
            } else if (cellText == detector->second.text) {
                chosen = cellText;
            } else if (contains(cellText, detector->second.text) ||
                       contains(detector->second.text, cellText)) {
                ++corrected;
                chosen =
                    reading.confidence >= detector->second.confidence ? cellText : detector->second.text;
            } else if (reading.confidence >= detector->second.confidence) {
                chosen = cellText;
            } else {
                ++corrected;
                chosen = detector->second.text;
            }

            matrix[cell.row][cell.column] = chosen;
            const bool fromDetector = detector != pass.byCell.end() &&
                                      chosen == detector->second.text && chosen != cellText;
            confidenceGrid[cell.row][cell.column] =
                fromDetector ? detector->second.confidence : reading.confidence;
        }

        int filled = 0;
        for (const auto& row : matrix) {
            for (const auto& value : row) {
                if (!value.empty()) ++filled;
            }
        }

        if (filled >= 2) {
            // 顺序很重要：先清坏列、裁两侧垃圾列，最后再做算术自检。
            // 反过来的话「最右一列是总分」会命中垃圾列，导出后出现两列一样的成绩。
            const TableRows cleaned = cleanNoisyColumns(cleanBadColumns(matrix));
            const TrimmedColumns trimmed = trimColumns(cleaned);
            const size_t width = trimmed.rows.empty() ? 0 : trimmed.rows.front().size();

            for (const auto& row : confidenceGrid) {
                std::vector<float> sliced;
                for (int column = trimmed.droppedLeading;
                     column < static_cast<int>(row.size()) && sliced.size() < width; ++column) {
                    sliced.push_back(row[static_cast<size_t>(column)]);
                }
                alignedConfidence.push_back(sliced);
            }

            // 表头要在自检之前认：表头那行全是文字，会让「数字列」「求和」全判错
            analysis.header = detectHeader(trimmed.rows);
            const TableRows body = analysis.header.fromImage
                                       ? TableRows(trimmed.rows.begin() + 1, trimmed.rows.end())
                                       : trimmed.rows;
            const TableConfidence bodyConfidence =
                analysis.header.fromImage && !alignedConfidence.empty()
                    ? TableConfidence(alignedConfidence.begin() + 1, alignedConfidence.end())
                    : alignedConfidence;

            const CheckResult checked = selfCheck(body, &bodyConfidence, &analysis.header);
            analysis.report = checked.report;
            // 分数列里 5→S、0→O 这类错字按列纠正：整列大多是数字时才动手
            const TableRows repaired = repairNumbers(checked.rows);
            if (analysis.header.fromImage && !trimmed.rows.empty()) {
                rows.push_back(trimmed.rows.front());
            }
            for (const auto& row : repaired) rows.push_back(row);
            corrected += checked.report.correctedCells;
        }
    }

    if (rows.empty()) {
        // 没有线：按文字间距聚类，至少还能给出一张能用的表
        if (gridFromClusters(engine, work, rows)) analysis.stats.byLines = false;
    }

    if (!rows.empty()) {
        if (const auto grid = normalizeCells(rows, analysis.stats.byLines)) {
            analysis.rows = grid->rows;
        } else {
            analysis.rows = rows;
        }
    }

    analysis.report.correctedCells = corrected;
    analysis.stats.correctedCells = corrected;

    float minConfidence = 0.0f;
    float maxConfidence = 0.0f;
    long long scored = 0;
    double sum = 0.0;
    int low = 0;
    bool first = true;
    for (size_t r = 0; r < alignedConfidence.size(); ++r) {
        for (size_t c = 0; c < alignedConfidence[r].size(); ++c) {
            // 表头行是文字，不参与置信度统计
            if (analysis.header.fromImage && r == 0) continue;
            const float value = alignedConfidence[r][c];
            if (value <= 0.0f) continue;
            ++scored;
            sum += value;
            if (first) {
                minConfidence = maxConfidence = value;
                first = false;
            } else {
                minConfidence = std::min(minConfidence, value);
                maxConfidence = std::max(maxConfidence, value);
            }
            if (value < kDisplayTrustedConfidence) ++low;
        }
    }
    if (scored > 0) {
        analysis.stats.averageConfidence = static_cast<float>(sum / static_cast<double>(scored));
        analysis.stats.confidenceMin = minConfidence;
        analysis.stats.confidenceMax = maxConfidence;
    }
    analysis.stats.lowConfidenceCells = low;

    analysis.working = work;
    analysis.overlay = drawGridOverlay(work, detection);
    analysis.enhanced = enhanced;
    analysis.stats.elapsedMs = elapsedMs(startAll);
    return analysis;
}

}  // namespace ocr
