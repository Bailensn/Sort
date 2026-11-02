#include "table/LineDetector.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

#include "imgproc/Binary.hpp"
#include "imgproc/Gray.hpp"
#include "ocr/ImageOps.hpp"

namespace ocr {
namespace {

constexpr double kMaxAnalysisSide = 1600.0;
constexpr double kMinLineRatio = 0.3;
constexpr int kFitWindow = 32;
constexpr float kMaxSlope = 0.15f;

struct Band {
    int first = 0;
    int last = 0;
};

std::vector<Band> findBands(const std::vector<uint8_t>& mask, int width, int height) {
    std::vector<Band> bands;
    if (width <= 0 || height <= 0) return bands;
    const int minCount = std::max(1, static_cast<int>(width * kMinLineRatio));

    int start = -1;
    for (int row = 0; row <= height; ++row) {
        bool hit = false;
        if (row < height) {
            int count = 0;
            const size_t base = static_cast<size_t>(row) * static_cast<size_t>(width);
            for (int x = 0; x < width; ++x) {
                if (mask[base + static_cast<size_t>(x)] != 0) ++count;
            }
            hit = count >= minCount;
        }
        if (hit && start < 0) start = row;
        if (!hit && start >= 0) {
            bands.push_back(Band{start, row - 1});
            start = -1;
        }
    }
    return bands;
}

/// 在一条线带里按窗口取质心，最小二乘拟合。
std::optional<FittedLine> fitBand(const std::vector<uint8_t>& mask, int width, int height, const Band& band,
                                  double scale) {
    const int top = std::max(band.first - 3, 0);
    const int bottom = std::min(band.last + 3, height - 1);
    const int bandHeight = bottom - top + 1;
    if (bandHeight <= 0 || width <= 0) return std::nullopt;

    std::vector<std::pair<double, double>> points;
    for (int x = 0; x < width;) {
        const int end = std::min(x + kFitWindow, width);
        double sumY = 0;
        int count = 0;
        for (int column = x; column < end; ++column) {
            for (int row = 0; row < bandHeight; ++row) {
                const size_t index = static_cast<size_t>(top + row) * static_cast<size_t>(width) +
                                     static_cast<size_t>(column);
                if (mask[index] != 0) {
                    sumY += row;
                    ++count;
                }
            }
        }
        if (count >= 2) points.emplace_back((x + end - 1) / 2.0, top + sumY / count);
        x = end;
    }
    if (points.size() < 4) return std::nullopt;

    double meanX = 0;
    double meanY = 0;
    for (const auto& point : points) {
        meanX += point.first;
        meanY += point.second;
    }
    meanX /= static_cast<double>(points.size());
    meanY /= static_cast<double>(points.size());

    double sxx = 0;
    double sxy = 0;
    for (const auto& point : points) {
        const double dx = point.first - meanX;
        sxx += dx * dx;
        sxy += dx * (point.second - meanY);
    }
    if (sxx < 1e-6) return std::nullopt;

    const double slope = sxy / sxx;
    if (std::abs(slope) > kMaxSlope) return std::nullopt;

    FittedLine line;
    line.slope = static_cast<float>(slope);
    line.intercept = static_cast<float>((meanY - slope * meanX) / scale);
    return line;
}

std::vector<uint8_t> transpose(const std::vector<uint8_t>& src, int width, int height) {
    std::vector<uint8_t> out(static_cast<size_t>(width) * static_cast<size_t>(height), 0);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            out[static_cast<size_t>(x) * static_cast<size_t>(height) + static_cast<size_t>(y)] =
                src[static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)];
        }
    }
    return out;
}

std::vector<FittedLine> mergeLines(std::vector<FittedLine> lines, float center) {
    if (lines.empty()) return lines;
    std::sort(lines.begin(), lines.end(), [center](const FittedLine& a, const FittedLine& b) {
        return a.valueAt(center) < b.valueAt(center);
    });
    std::vector<FittedLine> merged;
    for (const auto& line : lines) {
        if (!merged.empty() && std::abs(line.valueAt(center) - merged.back().valueAt(center)) < 6.0f) {
            FittedLine& last = merged.back();
            last = FittedLine{(line.slope + last.slope) / 2.0f, (line.intercept + last.intercept) / 2.0f};
        } else {
            merged.push_back(line);
        }
    }
    return merged;
}

}  // namespace

GridLines LineDetection::toGridLines() const {
    GridLines lines;
    for (const auto& line : horizontal) {
        lines.horizontal.push_back(line.valueAt(static_cast<float>(width) / 2.0f));
    }
    for (const auto& line : vertical) {
        lines.vertical.push_back(line.valueAt(static_cast<float>(height) / 2.0f));
    }
    return lines;
}

LineDetection detectLines(const Image8& image) {
    LineDetection detection;
    detection.width = image.width;
    detection.height = image.height;
    if (image.width <= 0 || image.height <= 0) return detection;

    const std::vector<uint8_t> gray = toGray(image);
    if (gray.empty()) return detection;

    // 太大就缩到 1600：线检测只需要相对位置，后面按 scale 折回原图
    const double scale = std::min(1.0, kMaxAnalysisSide / std::max(image.width, image.height));
    int width = image.width;
    int height = image.height;
    std::vector<uint8_t> work = gray;
    if (scale < 1.0) {
        Image8 grayImage;
        grayImage.width = image.width;
        grayImage.height = image.height;
        grayImage.order = ColorOrder::RGB;
        grayImage.data.resize(gray.size() * 3);
        for (size_t i = 0; i < gray.size(); ++i) {
            grayImage.data[i * 3] = gray[i];
            grayImage.data[i * 3 + 1] = gray[i];
            grayImage.data[i * 3 + 2] = gray[i];
        }
        const Image8 small = resize(grayImage, static_cast<int>(image.width * scale),
                                    static_cast<int>(image.height * scale), Interp::Linear);
        work = toGray(small);
        width = small.width;
        height = small.height;
    }
    if (width <= 0 || height <= 0) return detection;

    flattenLighting(work, width, height);
    const std::vector<uint8_t> binary = adaptiveThresholdMean(work, width, height, 15, 10.0);

    const int horizontalKernel = std::max(20, width / 25);
    const int verticalKernel = std::max(20, height / 25);
    const std::vector<uint8_t> horizontalMask = morphOpen(binary, width, height, horizontalKernel, 1);
    const std::vector<uint8_t> verticalMask = morphOpen(binary, width, height, 1, verticalKernel);

    for (const auto& band : findBands(horizontalMask, width, height)) {
        if (const auto line = fitBand(horizontalMask, width, height, band, scale)) {
            detection.horizontal.push_back(*line);
        }
    }

    const std::vector<uint8_t> transposed = transpose(verticalMask, width, height);
    for (const auto& band : findBands(transposed, height, width)) {
        if (const auto line = fitBand(transposed, height, width, band, scale)) {
            detection.vertical.push_back(*line);
        }
    }

    detection.horizontal = mergeLines(detection.horizontal, static_cast<float>(image.width) / 2.0f);
    detection.vertical = mergeLines(detection.vertical, static_cast<float>(image.height) / 2.0f);
    return detection;
}

}  // namespace ocr
