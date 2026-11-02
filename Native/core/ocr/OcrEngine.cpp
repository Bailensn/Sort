#include "OcrEngine.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>

#include "Charset.hpp"
#include "DbPostprocess.hpp"
#include "ImageOps.hpp"
#include "Postprocess.hpp"
#include "Preprocess.hpp"
#include "QuadCrop.hpp"

namespace ocr {
namespace {

constexpr int kCellInputHeight = 48;
constexpr int kMaxCellBatch = 16;
constexpr int64_t kMaxCellBatchBytes = 24 * 1024 * 1024;
constexpr int kChunkCells = 64;
constexpr int kSmallTextHeight = 28;

using Clock = std::chrono::steady_clock;

double elapsedMs(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

/// 送进 rec 之前先估宽：高固定 48，按长宽比换算。
int estimatedWidth(const Image8& crop) {
    if (crop.height <= 0) return 16;
    const int width = static_cast<int>(kCellInputHeight * static_cast<double>(crop.width) / crop.height);
    return std::min(std::max(width, 16), 3200);
}

Image8 cropCell(const Image8& src, const Rect& cell, float scale) {
    if (cell.width() <= 2 || cell.height() <= 2) return Image8{};

    const int inset = std::max(2, std::min(cell.width(), cell.height()) / 40);
    const int left = std::min(std::max(cell.left + inset, 0), src.width - 1);
    const int top = std::min(std::max(cell.top + inset, 0), src.height - 1);
    const int right = std::min(std::max(cell.right - inset, left + 1), src.width);
    const int bottom = std::min(std::max(cell.bottom - inset, top + 1), src.height);
    if (right - left < 3 || bottom - top < 3) return Image8{};

    const Image8 roi = crop(src, Rect{left, top, right, bottom});
    if (roi.empty()) return Image8{};

    // 低置信度格子会用 scale > 1 再认一次，这时不看高度一律放大。
    double factor = 1.0;
    if (scale > 1.0f) {
        factor = scale;
    } else if (roi.height < kSmallTextHeight) {
        factor = std::min(std::max(static_cast<double>(kSmallTextHeight) / roi.height, 1.5), 3.0);
    }

    Image8 source = std::move(roi);
    if (factor > 1.0) {
        const double bounded = std::min(factor, 4.0);
        source = resize(roi, static_cast<int>(roi.width * bounded),
                        static_cast<int>(roi.height * bounded), Interp::Cubic);
    }
    if (source.empty()) return source;

    const int padding = std::max(4, source.height / 8);
    return copyMakeBorder(source, padding, padding, padding, padding, 255);
}

}  // namespace

OcrEngine::OcrEngine(OcrConfig config) : config_(config) {}

void OcrEngine::setBackend(std::unique_ptr<InferenceBackend> backend) {
    backend_ = std::move(backend);
}

bool OcrEngine::setCharset(std::vector<std::string> charset) {
    if (charset.empty()) return false;
    charset_ = std::move(charset);
    return true;
}

bool OcrEngine::loadCharsetFromFile(const std::string& path) {
    std::vector<std::string> charset;
    if (!loadCharset(path, charset)) return false;
    charset_ = std::move(charset);
    return true;
}

std::vector<OcrItem> OcrEngine::recognizeBoxes(const Image8& image, const std::vector<Box>& boxes,
                                               double& recognitionMs) {
    std::vector<OcrItem> items;
    if (boxes.empty()) {
        recognitionMs = 0.0;
        return items;
    }

    const auto start = Clock::now();
    const int batchSize = std::max(1, config_.recBatchSize);

    size_t index = 0;
    while (index < boxes.size()) {
        std::vector<Image8> crops;
        std::vector<size_t> boxIndices;
        crops.reserve(static_cast<size_t>(batchSize));
        boxIndices.reserve(static_cast<size_t>(batchSize));

        while (index < boxes.size() && static_cast<int>(crops.size()) < batchSize) {
            Image8 crop = quadCrop(image, boxes[index]);
            if (!crop.empty()) {
                crops.push_back(std::move(crop));
                boxIndices.push_back(index);
            }
            ++index;
        }

        if (crops.empty()) continue;

        const RecPreprocessResult pre = preprocessRec(crops);
        Tensor output;
        if (!backend_ || !backend_->runRec(pre.tensor, output)) continue;

        const auto decoded = ctcDecode(output, charset_);
        for (size_t j = 0; j < decoded.size() && j < boxIndices.size(); ++j) {
            if (decoded[j].second < config_.recScoreThresh) continue;
            OcrItem item;
            item.box = boxes[boxIndices[j]];
            item.text = decoded[j].first;
            item.confidence = decoded[j].second;
            items.push_back(std::move(item));
        }
    }

    recognitionMs = elapsedMs(start);
    return items;
}

OcrResult OcrEngine::recognize(const Image8& image) {
    OcrResult result;
    if (image.empty() || !backend_ || charset_.empty()) return result;

    const auto totalStart = Clock::now();

    const auto detStart = Clock::now();
    const DetPreprocessResult det = preprocessDet(image, config_);
    Tensor detOutput;
    if (!backend_->runDet(det.tensor, detOutput)) return result;

    std::vector<Box> boxes =
        dbPostprocess(detOutput, det.originalWidth, det.originalHeight, config_);
    result.detectionMs = elapsedMs(detStart);

    if (boxes.empty()) {
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    sortInReadingOrder(boxes);
    result.items = recognizeBoxes(image, boxes, result.recognitionMs);
    result.totalMs = elapsedMs(totalStart);
    return result;
}

void OcrEngine::runCellBatch(const std::vector<int>& indices, const std::vector<Image8>& crops,
                             std::vector<CellReading>& output, InferenceBackend* backend,
                             const std::vector<std::string>& charset) {
    if (indices.empty()) return;

    // 先按宽度排序再成组：同批宽高接近，补边的浪费最小。
    std::vector<size_t> order(indices.size());
    for (size_t i = 0; i < order.size(); ++i) order[i] = i;
    std::sort(order.begin(), order.end(), [&](size_t a, size_t b) {
        return estimatedWidth(crops[a]) < estimatedWidth(crops[b]);
    });

    size_t start = 0;
    while (start < order.size()) {
        size_t end = start;
        int64_t batchWidth = 0;
        while (end < order.size() && static_cast<int>(end - start) < kMaxCellBatch) {
            const int64_t width = estimatedWidth(crops[order[end]]);
            const int64_t bytes = (batchWidth + width) * kCellInputHeight * 3 * 4;
            if (end > start && bytes > kMaxCellBatchBytes) break;
            batchWidth += width;
            ++end;
        }

        std::vector<const Image8*> sliceCrops;
        std::vector<int> sliceIndices;
        sliceCrops.reserve(end - start);
        sliceIndices.reserve(end - start);
        for (size_t i = start; i < end; ++i) {
            sliceCrops.push_back(&crops[order[i]]);
            sliceIndices.push_back(indices[order[i]]);
        }

        const RecPreprocessResult pre = preprocessRec(sliceCrops);
        Tensor decodedTensor;
        if (backend == nullptr || !backend->runRec(pre.tensor, decodedTensor)) {
            for (int index : sliceIndices) {
                output[static_cast<size_t>(index)] = CellReading{"", 0.0f, true};
            }
            start = end;
            continue;
        }

        const auto decoded = ctcDecode(decodedTensor, charset);
        for (size_t i = 0; i < sliceIndices.size(); ++i) {
            if (i >= decoded.size()) {
                output[static_cast<size_t>(sliceIndices[i])] = CellReading{"", 0.0f, true};
                continue;
            }
            output[static_cast<size_t>(sliceIndices[i])] =
                CellReading{cleanCellText(decoded[i].first), decoded[i].second, true};
        }
        start = end;
    }
}

bool OcrEngine::setReferee(std::unique_ptr<InferenceBackend> backend,
                           std::vector<std::string> charset) {
    if (!backend || charset.empty()) return false;
    refereeBackend_ = std::move(backend);
    refereeCharset_ = std::move(charset);
    return true;
}

std::vector<CellReading> OcrEngine::recognizeCells(const Image8& image,
                                                   const std::vector<Rect>& cells, float scale,
                                                   bool referee) {
    std::vector<CellReading> output(cells.size(), CellReading{"", 0.0f, false});
    InferenceBackend* backend = referee ? refereeBackend_.get() : backend_.get();
    const std::vector<std::string>& charset = referee ? refereeCharset_ : charset_;
    if (image.empty() || cells.empty() || backend == nullptr || charset.empty()) return output;

    std::vector<int> pendingIndices;
    std::vector<Image8> pendingCrops;
    pendingIndices.reserve(kChunkCells);
    pendingCrops.reserve(kChunkCells);

    auto flush = [&]() {
        if (pendingCrops.empty()) return;
        runCellBatch(pendingIndices, pendingCrops, output, backend, charset);
        pendingIndices.clear();
        pendingCrops.clear();
    };

    for (size_t i = 0; i < cells.size(); ++i) {
        Image8 crop = cropCell(image, cells[i], scale);
        if (crop.empty()) continue;
        // 空白格不送模型，否则模型会在空框里“猜”出字。
        if (!hasInk(crop, 0.002)) continue;

        pendingIndices.push_back(static_cast<int>(i));
        pendingCrops.push_back(std::move(crop));
        if (static_cast<int>(pendingCrops.size()) >= kChunkCells) flush();
    }
    flush();
    return output;
}

}  // namespace ocr
