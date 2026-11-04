#include "table/TableAnalyzer.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <thread>
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
#include "table/LineErase.hpp"
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

constexpr float kThirdConfidence = 0.98f;
constexpr float kHopelessConfidence = 0.6f;
constexpr float kDisplayTrustedConfidence = 0.98f;
constexpr float kStrictScale = 2.0f;
constexpr float kThirdScale = 3.0f;
constexpr float kRefereeScale = 2.0f;
constexpr float kMinCellTextConfidence = 0.25f;
constexpr float kDetectorOverlap = 0.75f;
// 两条线离得比这还近，多半是同一条线被切成了两次，或者是表格外面的一条毛刺；
// 合掉它，免得最右边多出一列装不下字的垃圾列。
constexpr int kMinCellGap = 12;

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

/// 多遍投票：票多的赢，同票才比分数——两路独立认成同一串字比单路高分可靠。
CellReading pickBest(const std::vector<CellReading>& candidates) {
    if (candidates.empty()) return CellReading{};
    if (candidates.size() == 1) return candidates.front();

    const CellReading* best = nullptr;
    int bestVotes = 0;
    float bestTop = -1.0f;
    for (const auto& candidate : candidates) {
        const std::string text = trimText(candidate.text);
        if (text.empty()) continue;
        int votes = 0;
        float top = 0.0f;
        for (const auto& other : candidates) {
            if (trimText(other.text) != text) continue;
            ++votes;
            top = std::max(top, other.confidence);
        }
        if (votes > bestVotes || (votes == bestVotes && top > bestTop)) {
            bestVotes = votes;
            bestTop = top;
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

void report(const TableProgress& progress, TableStage stage, float fraction) {
    if (progress) progress(stage, fraction);
}

PassOutput recognizeDeep(OcrEngine& engine, const Image8& work, const Image8& enhanced,
                         const std::vector<Rect>& rects, const TableProgress& progress) {
    PassOutput pass;
    const auto cellStart = Clock::now();

    pass.readings = engine.recognizeCells(work, rects);
    report(progress, TableStage::Recognize, 0.45f);

    // 回读和逐格识别互不依赖：线程安全的后端就让整图识别在后面同时跑。
    OcrResult readback;
    std::thread worker;
    const auto readbackStart = Clock::now();
    if (engine.threadSafe()) {
        worker = std::thread([&engine, &work, &readback] {
            try {
                readback = engine.recognize(work);
            } catch (...) {
                readback = OcrResult{};
            }
        });
    }

    // 二遍：所有有墨迹的格子都换一代模型（增强图 2 倍）再认一次，两路独立才投得出票。
    std::vector<size_t> strict;
    for (size_t i = 0; i < pass.readings.size(); ++i) {
        if (pass.readings[i].hasInk) strict.push_back(i);
    }
    report(progress, TableStage::Recognize, 0.55f);
    if (!strict.empty() && !enhanced.empty()) {
        std::vector<Rect> strictRects;
        strictRects.reserve(strict.size());
        for (size_t index : strict) strictRects.push_back(rects[index]);
        // 二遍换一代模型（主 v6、第二遍 v4）：两个模型错的方式不一样，投票才有意义
        const OcrEngine::CellModel secondModel = engine.hasAlternate() ? OcrEngine::CellModel::Alternate
                                                                      : OcrEngine::CellModel::Primary;
        const std::vector<CellReading> second =
            engine.recognizeCells(enhanced, strictRects, kStrictScale, secondModel);

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
                engine.recognizeCells(enhanced, refereeRects, kRefereeScale,
                                      OcrEngine::CellModel::Referee);
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
    report(progress, TableStage::Recognize, 0.7f);
    if (!third.empty()) {
        std::vector<Rect> thirdRects;
        thirdRects.reserve(third.size());
        for (size_t index : third) thirdRects.push_back(rects[index]);

        const std::vector<CellReading> fromOriginal = engine.recognizeCells(work, thirdRects, kThirdScale);
        std::vector<CellReading> fromReferee;
        if (engine.hasReferee() && !enhanced.empty()) {
            const std::vector<CellReading> readings =
                engine.recognizeCells(enhanced, thirdRects, kRefereeScale,
                                      OcrEngine::CellModel::Referee);
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

    // 四遍：低到看不清的格子换软二值图再认一次，背景全白、笔画留灰度。
    std::vector<size_t> hopeless;
    for (size_t i = 0; i < pass.readings.size(); ++i) {
        const CellReading& reading = pass.readings[i];
        if (reading.hasInk && reading.confidence < kHopelessConfidence) hopeless.push_back(i);
    }
    report(progress, TableStage::Recognize, 0.8f);
    if (!hopeless.empty()) {
        std::vector<uint8_t> binaryGray = toGray(work);
        if (!binaryGray.empty()) {
            flattenLighting(binaryGray, work.width, work.height);
            const std::vector<uint8_t> soft = binarizeSoft(binaryGray, work.width, work.height);
            const Image8 softImage = grayToImage(soft, work.width, work.height);
            std::vector<Rect> hopelessRects;
            hopelessRects.reserve(hopeless.size());
            for (size_t index : hopeless) hopelessRects.push_back(rects[index]);
            const std::vector<CellReading> fromBinary =
                engine.recognizeCells(softImage, hopelessRects, kStrictScale);
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
    // 并行时记的是「等它」的耗时，串行时才是它自己的耗时。
    report(progress, TableStage::Verify, 0.85f);
    if (worker.joinable()) {
        const auto joinStart = Clock::now();
        worker.join();
        pass.detectMs = elapsedMs(joinStart);
    } else {
        try {
            readback = engine.recognize(work);
        } catch (...) {
            readback = OcrResult{};
        }
        pass.detectMs = elapsedMs(readbackStart);
    }

    for (const auto& item : readback.items) {
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

TableAnalysis analyzeTable(OcrEngine& engine, const Image8& image,
                           const TableProgress& progress) {
    const auto startAll = Clock::now();
    TableAnalysis analysis;
    if (image.empty()) return analysis;
    report(progress, TableStage::Prepare, 0.02f);

    // 第一步就把画面裁到有内容的那块：桌面、黑边、灰底不会带偏后面的检测
    Image8 work = image;
    if (const auto bounds = contentBounds(image)) {
        Image8 cropped = cropImage(image, *bounds);
        if (!cropped.empty()) work = std::move(cropped);
    }

    report(progress, TableStage::Prepare, 0.1f);
    const SkewResult skew = deskewImage(work);
    if (!skew.image.empty()) work = skew.image;
    analysis.stats.deskewDegrees = skew.degrees;

    report(progress, TableStage::Prepare, 0.2f);
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

    detection = extendEdgeLines(work, detection);
    analysis.stats.horizontalLines = static_cast<int>(detection.horizontal.size());
    analysis.stats.verticalLines = static_cast<int>(detection.vertical.size());
    const GridLines lines = detection.toGridLines();
    const int lineWidth = estimateLineWidth(work, lines);

    report(progress, TableStage::Prepare, 0.32f);
    // 识别用的底图：把表格线本身抹成纸白，线头残留会被认成「1」「|」。
    // 只抹线的粗细，不要往外扩：窄列（学号、姓名）的字离边线很近，抹宽了连字一起没了。
    const Image8 clean = eraseLines(work, detection, std::max(1, lineWidth / 2));

    // 二遍识别用：压平光照 + 拉对比度
    std::vector<uint8_t> enhancedGray = toGray(clean);
    Image8 enhanced;
    if (!enhancedGray.empty()) {
        flattenLighting(enhancedGray, work.width, work.height);
        clahe(enhancedGray, work.width, work.height, 3.0, 8);
        enhanced = grayToImage(enhancedGray, work.width, work.height);
    }

    // 预览也用软二值：硬切会把笔画边缘切成锯齿，看着就像认坏了。
    std::vector<uint8_t> displayGray = toGray(work);
    if (!displayGray.empty()) {
        flattenLighting(displayGray, work.width, work.height);
        analysis.enhanced =
            grayToImage(binarizeSoft(displayGray, work.width, work.height), work.width, work.height);
    }

    report(progress, TableStage::Segment, 0.38f);
    const CellLayout layout = buildCells(lines, work.width, work.height);
    analysis.stats.cellCount = static_cast<int>(layout.cells.size());

    TableRows rows;
    TableConfidence alignedConfidence;
    int corrected = 0;

    if (!layout.cells.empty()) {
        report(progress, TableStage::Recognize, 0.4f);
        const std::vector<Rect> rects = insetRects(layout.cells, lineWidth + 1);
        const PassOutput pass = recognizeDeep(engine, clean, enhanced, rects, progress);

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
                // 逐格没认出来就留空。以前拿整图回读去补，补进来的多半是线头
                chosen = cellText;
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

            // 学号列先补同形字，自检的连续性才不会被一个 O 打断
            const TableRows identified = repairIdentifiers(trimmed.rows);
            // 表头要在自检之前认：表头那行全是文字，会让「数字列」「求和」全判错
            analysis.header = detectHeader(identified);
            // 表头行就是第一行数据，自检时跳过它，但不从表里摘出去
            const size_t headerRows = analysis.header.fromImage ? 1 : 0;
            const TableRows body(identified.begin() + static_cast<long>(headerRows),
                                 identified.end());
            const TableConfidence bodyConfidence =
                headerRows == 1 && !alignedConfidence.empty()
                    ? TableConfidence(alignedConfidence.begin() + 1, alignedConfidence.end())
                    : alignedConfidence;

            const CheckResult checked = selfCheck(body, &bodyConfidence, &analysis.header);
            analysis.report = checked.report;
            // 分数列里 5→S、0→O 这类错字按列纠正：整列大多是数字时才动手
            const TableRows repaired = repairNumbers(checked.rows);
            rows = identified;
            for (size_t i = 0; i < repaired.size() && headerRows + i < rows.size(); ++i) {
                rows[headerRows + i] = repaired[i];
            }
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

    report(progress, TableStage::Verify, 0.95f);
    analysis.working = work;
    analysis.overlay = drawGridOverlay(work, detection);
    analysis.stats.elapsedMs = elapsedMs(startAll);
    report(progress, TableStage::Verify, 1.0f);
    return analysis;
}

}  // namespace ocr
