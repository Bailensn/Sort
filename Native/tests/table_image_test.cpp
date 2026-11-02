// 合成表格图的几何回归：不依赖模型，验证裁切、纠偏、找线、外框、叠线。
#include <cmath>
#include <cstdio>
#include <vector>

#include "imgproc/Gray.hpp"
#include "imgproc/Warp.hpp"
#include "table/Cropper.hpp"
#include "table/EdgeLines.hpp"
#include "table/LineDetector.hpp"
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
    for (int row = 0; row < rows; ++row) {
        for (int column = 0; column < columns; ++column) {
            const int x = column * cellWidth + cellWidth / 5;
            const int y = row * cellHeight + cellHeight / 2;
            fillRect(image, x, y, x + cellWidth / 3, y + 6, 60);
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
    testGrayOrder();
    testAnalyzeWithoutBackend();
    if (g_failed == 0) {
        std::printf("table image: 全部通过\n");
        return 0;
    }
    std::printf("table image: %d 项失败\n", g_failed);
    return 1;
}
