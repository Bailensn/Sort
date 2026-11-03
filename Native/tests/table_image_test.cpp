// 合成表格图的几何回归：不依赖模型，验证裁切、纠偏、找线、外框、叠线。
#include <cmath>
#include <cstdio>
#include <vector>

#include "imgproc/Binary.hpp"
#include "imgproc/Gray.hpp"
#include "imgproc/Threshold.hpp"
#include "imgproc/Warp.hpp"
#include "table/Cropper.hpp"
#include "table/EdgeLines.hpp"
#include "table/LineDetector.hpp"
#include "table/LineErase.hpp"
#include "table/LineWidth.hpp"
#include "table/Overlay.hpp"
#include "table/QuadFit.hpp"
#include "table/Skew.hpp"
#include "table/TableAnalyzer.hpp"

namespace {

int g_failed = 0;

void expect(bool condition, const char* what) {
    if (condition) return;
    std::printf("FAIL %s\n", what);
    ++g_failed;
}

ocr::Image8 makeCanvas(int width, int height, int value) {
    ocr::Image8 image;
    image.width = width;
    image.height = height;
    image.order = ocr::ColorOrder::RGB;
    image.data.assign(static_cast<size_t>(width) * static_cast<size_t>(height) * 3u,
                      static_cast<uint8_t>(value));
    return image;
}

void fillRect(ocr::Image8& image, int left, int top, int right, int bottom, int value) {
    for (int y = std::max(0, top); y < std::min(image.height, bottom); ++y) {
        uint8_t* row = image.row(y);
        for (int x = std::max(0, left); x < std::min(image.width, right); ++x) {
            row[static_cast<size_t>(x) * 3u] = static_cast<uint8_t>(value);
            row[static_cast<size_t>(x) * 3u + 1] = static_cast<uint8_t>(value);
            row[static_cast<size_t>(x) * 3u + 2] = static_cast<uint8_t>(value);
        }
    }
}

/// 5 列 6 行的成绩单：外框 + 单元格里几块「字」。
ocr::Image8 makeTable(int columns, int rows, int cellWidth, int cellHeight) {
    const int width = columns * cellWidth;
    const int height = rows * cellHeight;
    ocr::Image8 image = makeCanvas(width, height, 245);
    for (int row = 0; row <= rows; ++row) {
        const int y = std::min(height - 2, row * cellHeight);
        fillRect(image, 0, y, width, y + 2, 40);
    }
    for (int column = 0; column <= columns; ++column) {
        const int x = std::min(width - 2, column * cellWidth);
        fillRect(image, x, 0, x + 2, height, 40);
    }
    // 用带间隙的小字块，别画成实心长条：长条会被当成表格线
    for (int row = 0; row < rows; ++row) {
        for (int column = 0; column < columns; ++column) {
            const int baseX = column * cellWidth + cellWidth / 6;
            const int y = row * cellHeight + cellHeight / 2;
            for (int glyph = 0; glyph < 4; ++glyph) {
                const int x = baseX + glyph * 14;
                fillRect(image, x, y, x + 8, y + 18, 60);
            }
        }
    }
    return image;
}

/// 棋盘格块：行列两个方向的墨迹占比都稳定，才能测裁切。
void testContentBounds() {
    ocr::Image8 canvas = makeCanvas(1000, 800, 250);
    const int left = 250;
    const int top = 200;
    const int right = 750;
    const int bottom = 600;
    const int block = 10;
    for (int y = top; y < bottom; ++y) {
        for (int x = left; x < right; ++x) {
            const bool dark = ((x - left) / block + (y - top) / block) % 2 == 0;
            if (!dark) continue;
            uint8_t* pixel = canvas.row(y) + static_cast<size_t>(x) * 3u;
            pixel[0] = 60;
            pixel[1] = 60;
            pixel[2] = 60;
        }
    }

    const auto bounds = ocr::contentBounds(canvas);
    expect(bounds.has_value(), "contentBounds 应当找到内容");
    if (!bounds) return;
    expect(std::abs(bounds->left - left) <= 20, "contentBounds 左边应贴住内容");
    expect(std::abs(bounds->top - top) <= 20, "contentBounds 上边应贴住内容");
    expect(std::abs(bounds->right - right) <= 20, "contentBounds 右边应贴住内容");
    expect(std::abs(bounds->bottom - bottom) <= 20, "contentBounds 下边应贴住内容");
}

void testLineDetectionAndQuad() {
    const ocr::Image8 table = makeTable(4, 3, 150, 100);
    const ocr::LineDetection detection = ocr::detectLines(table);
    expect(detection.usable(), "线检测应当可用");
    expect(detection.horizontal.size() >= 3, "横线至少 3 条");
    expect(detection.vertical.size() >= 3, "竖线至少 3 条");

    const auto quad = ocr::fitTableQuad(table, detection);
    expect(quad.has_value(), "应当能求出外框");
    if (quad) {
        expect(std::abs(quad->corners[0].x) < 12.0f && std::abs(quad->corners[0].y) < 12.0f,
               "左上角应贴住画面左上");
        expect(std::abs(quad->corners[2].x - table.width) < 12.0f &&
                   std::abs(quad->corners[2].y - table.height) < 12.0f,
               "右下角应贴住画面右下");
    }

    const ocr::GridLines lines = detection.toGridLines();
    const int thickness = ocr::estimateLineWidth(table, lines);
    expect(thickness >= 1 && thickness <= 5, "线宽估计应在 1~5");
}

void testSkew() {
    const ocr::Image8 table = makeTable(4, 3, 150, 100);
    const ocr::Image8 tilted = ocr::rotateImage(table, 2.5);
    const ocr::SkewResult deskewed = ocr::deskewImage(tilted);
    expect(std::abs(deskewed.degrees) > 1.0, "应当测出 2.5 度左右的倾斜");

    const ocr::LineDetection detection = ocr::detectLines(deskewed.image);
    expect(detection.usable(), "转正后仍应检测到线");
    for (const auto& line : detection.horizontal) {
        expect(std::abs(line.slope) < 0.03f, "转正后横线斜率应接近 0");
    }
}

void testEdgeLinesAndOverlay() {
    const ocr::Image8 table = makeTable(4, 3, 150, 100);
    ocr::LineDetection detection = ocr::detectLines(table);
    const ocr::LineDetection extended = ocr::extendEdgeLines(table, detection);
    expect(extended.horizontal.size() >= detection.horizontal.size(), "补边线不应减少横线");
    expect(extended.vertical.size() >= detection.vertical.size(), "补边线不应减少竖线");

    const ocr::Image8 overlay = ocr::drawGridOverlay(table, detection);
    expect(overlay.width == table.width && overlay.height == table.height, "叠线图尺寸应不变");
    int changed = 0;
    for (size_t i = 0; i < overlay.data.size(); ++i) {
        if (overlay.data[i] != table.data[i]) ++changed;
    }
    expect(changed > 0, "叠线图应当真的画上了线");
}

/// 逐点扫描版，用来证明可分离实现没改行为。
std::vector<uint8_t> naiveMorphOpen(const std::vector<uint8_t>& binary, int width, int height, int kw,
                                    int kh) {
    const int rx = std::max(0, kw / 2);
    const int ry = std::max(0, kh / 2);
    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    std::vector<uint8_t> eroded(pixels, 0);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            bool all = true;
            for (int dy = -ry; dy <= ry && all; ++dy) {
                for (int dx = -rx; dx <= rx; ++dx) {
                    const int sx = std::clamp(x + dx, 0, width - 1);
                    const int sy = std::clamp(y + dy, 0, height - 1);
                    if (binary[static_cast<size_t>(sy) * width + static_cast<size_t>(sx)] == 0) {
                        all = false;
                        break;
                    }
                }
            }
            eroded[static_cast<size_t>(y) * width + static_cast<size_t>(x)] = all ? 255 : 0;
        }
    }
    std::vector<uint8_t> dilated(pixels, 0);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            bool any = false;
            for (int dy = -ry; dy <= ry && !any; ++dy) {
                for (int dx = -rx; dx <= rx; ++dx) {
                    const int sx = std::clamp(x + dx, 0, width - 1);
                    const int sy = std::clamp(y + dy, 0, height - 1);
                    if (eroded[static_cast<size_t>(sy) * width + static_cast<size_t>(sx)] != 0) {
                        any = true;
                        break;
                    }
                }
            }
            dilated[static_cast<size_t>(y) * width + static_cast<size_t>(x)] = any ? 255 : 0;
        }
    }
    return dilated;
}

void testMorphology() {
    const int width = 41;
    const int height = 29;
    std::vector<uint8_t> binary(static_cast<size_t>(width) * height, 0);
    unsigned state = 7u;
    for (auto& value : binary) {
        // xorshift32：随便造点噪声，别用看起来像学号的常数
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        value = ((state >> 11) & 1u) != 0 ? 255 : 0;
    }
    for (int x = 0; x < width; ++x) binary[static_cast<size_t>(10) * width + static_cast<size_t>(x)] = 255;
    for (int y = 0; y < height; ++y) binary[static_cast<size_t>(y) * width + 7] = 255;

    const int kernels[][2] = {{1, 1}, {3, 1}, {9, 1}, {1, 3}, {1, 9}, {7, 5}, {21, 1}, {1, 21}};
    for (const auto& kernel : kernels) {
        const std::vector<uint8_t> fast = ocr::morphOpen(binary, width, height, kernel[0], kernel[1]);
        const std::vector<uint8_t> slow = naiveMorphOpen(binary, width, height, kernel[0], kernel[1]);
        expect(fast == slow, "可分离形态学必须和逐点扫描等价");
    }
}

/// 识别用的黑白图必须是「白纸黑字」，不能跟线检测的反相掩膜搞反。
void testBinaryPolarity() {
    std::vector<uint8_t> gray(100, 240);
    for (int i = 30; i < 40; ++i) gray[static_cast<size_t>(i)] = 30;

    const std::vector<uint8_t> binary = ocr::binarizeForText(gray);
    expect(binary.size() == gray.size(), "二值图尺寸应不变");
    if (binary.size() != gray.size()) return;
    expect(binary[0] == 255, "纸面应是白的");
    expect(binary[35] == 0, "笔画应是黑的");

    const std::vector<uint8_t> inverted = ocr::thresholdInverse(gray, 128);
    expect(inverted[0] == 0 && inverted[35] == 255, "线检测那套是反相掩膜，两者不能混用");
}

/// 安卓传进来的是 BGR：通道顺序读错会让彩色字迹的灰度整体偏掉。
void testGrayOrder() {
    ocr::Image8 rgb;
    rgb.width = 2;
    rgb.height = 1;
    rgb.order = ocr::ColorOrder::RGB;
    rgb.data = {255, 0, 0, 0, 0, 255};
    const std::vector<uint8_t> rgbGray = ocr::toGray(rgb);
    expect(rgbGray.size() == 2, "灰度图尺寸应等于像素数");
    if (rgbGray.size() != 2) return;
    expect(std::abs(rgbGray[0] - 76) <= 1, "纯红的灰度应是 76 左右");
    expect(std::abs(rgbGray[1] - 28) <= 1, "纯蓝的灰度应是 28 左右");

    ocr::Image8 bgr = rgb;
    bgr.order = ocr::ColorOrder::BGR;
    const std::vector<uint8_t> bgrGray = ocr::toGray(bgr);
    expect(bgrGray.size() == 2, "BGR 灰度图尺寸应等于像素数");
    if (bgrGray.size() != 2) return;
    expect(bgrGray[0] == rgbGray[1] && bgrGray[1] == rgbGray[0], "同一个缓冲换个顺序，红蓝应互换");
}

/// 一列对齐的竖笔画不能被当成表格线：那会把「7.1」劈成 7 和 1 两格。
void testStemsAreNotLines() {
    const int columns = 4;
    const int rows = 5;
    const int cellWidth = 150;
    const int cellHeight = 100;
    ocr::Image8 table = makeTable(columns, rows, cellWidth, cellHeight);

    // 在第 0 列里竖着摆一串又细又高的笔画，整体够高、但中间全是断口
    const int stemX = 75;
    for (int row = 0; row < rows; ++row) {
        const int top = row * cellHeight + 8;
        fillRect(table, stemX, top, stemX + 2, top + 74, 40);
    }

    const ocr::LineDetection detection = ocr::detectLines(table);
    expect(detection.usable(), "加了竖笔画也要能检测到真正的表格线");
    for (const auto& line : detection.vertical) {
        const float x = line.valueAt(static_cast<float>(table.height) / 2.0f);
        expect(std::abs(x - stemX) > 12.0f, "竖笔画那一列不该被当成表格线");
    }
}

/// 去线：线上的像素变白，格子里的字还在。
void testEraseLines() {
    const ocr::Image8 table = makeTable(4, 3, 150, 100);
    const ocr::LineDetection detection = ocr::detectLines(table);
    expect(detection.usable(), "去线前应先检测到线");
    if (!detection.usable()) return;

    const ocr::Image8 clean = ocr::eraseLines(table, detection, 3);
    expect(clean.width == table.width && clean.height == table.height, "去线后尺寸不变");

    // 第一条横线正中取几个点，都应该是纸白
    const float y = detection.horizontal.front().valueAt(static_cast<float>(table.width) / 2);
    const int lineY = static_cast<int>(std::lround(y));
    int white = 0;
    for (int x = 10; x < table.width - 10; x += 25) {
        const uint8_t* pixel = clean.row(lineY) + static_cast<size_t>(x) * 3u;
        if (pixel[0] > 240 && pixel[1] > 240 && pixel[2] > 240) ++white;
    }
    expect(white > 0, "横线上的像素应被抹白");

    // 格子中间的字不能跟着一起没
    int ink = 0;
    for (int row = 0; row < 3; ++row) {
        for (int column = 0; column < 4; ++column) {
            const int x = column * 150 + 150 / 6 + 2;
            const int yy = row * 100 + 100 / 2 + 9;
            const uint8_t* pixel = clean.row(yy) + static_cast<size_t>(x) * 3u;
            if (pixel[0] < 200) ++ink;
        }
    }
    expect(ink > 0, "格子里的字不该被去线抹掉");
}

/// 没有推理后端时整条链路也要能跑通：不崩、给出工作图和叠线图、行留空。
void testAnalyzeWithoutBackend() {
    const ocr::Image8 table = makeTable(4, 3, 150, 100);
    ocr::OcrEngine engine;
    const ocr::TableAnalysis analysis = ocr::analyzeTable(engine, table);
    expect(analysis.rows.empty(), "没有后端时不应有行数据");
    expect(!analysis.working.empty(), "应当返回工作图");
    expect(analysis.overlay.width == analysis.working.width &&
               analysis.overlay.height == analysis.working.height,
           "叠线图应与工作图同尺寸");
    expect(analysis.stats.elapsedMs >= 0.0, "应当有耗时");
}

}  // namespace

int main() {
    testContentBounds();
    testLineDetectionAndQuad();
    testSkew();
    testEdgeLinesAndOverlay();
    testBinaryPolarity();
    testMorphology();
    testGrayOrder();
    testStemsAreNotLines();
    testEraseLines();
    testAnalyzeWithoutBackend();
    if (g_failed == 0) {
        std::printf("table image: 全部通过\n");
        return 0;
    }
    std::printf("table image: %d 项失败\n", g_failed);
    return 1;
}
