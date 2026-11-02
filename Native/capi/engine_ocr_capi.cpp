#include "engine_ocr_capi.h"

#include <exception>
#include <memory>
#include <string>
#include <vector>

#include "BackendFactory.hpp"
#include "ocr/OcrEngine.hpp"

struct OcrHandle {
    ocr::OcrEngine engine;
};

namespace {

/// 把调用方的缓冲拷成 Image8；stride 不为 0 时按行拷贝，跳过行尾填充。
bool copyImage(const uint8_t* pixels, int32_t width, int32_t height, int32_t stride, int32_t isBgr,
               ocr::Image8& out) {
    if (pixels == nullptr || width <= 0 || height <= 0) return false;
    const int32_t rowBytes = width * 3;
    if (stride != 0 && stride < rowBytes) return false;

    out.width = width;
    out.height = height;
    out.order = isBgr != 0 ? ocr::ColorOrder::BGR : ocr::ColorOrder::RGB;
    out.data.resize(static_cast<size_t>(width) * static_cast<size_t>(height) * 3u);

    const int32_t sourceStride = stride != 0 ? stride : rowBytes;
    for (int32_t y = 0; y < height; ++y) {
        const uint8_t* src = pixels + static_cast<size_t>(y) * static_cast<size_t>(sourceStride);
        uint8_t* dst = out.data.data() + static_cast<size_t>(y) * static_cast<size_t>(rowBytes);
        for (int32_t i = 0; i < rowBytes; ++i) dst[i] = src[i];
    }
    return true;
}

}  // namespace

extern "C" {

namespace {
int g_lastError = ENGINE_OK;
}  // namespace

int32_t engine_ocr_last_error(void) { return g_lastError; }

OcrHandle *engine_ocr_create(const char *detModelPath, const char *recModelPath,
                             const char *charsetPath, int32_t numThreads) {
    if (detModelPath == nullptr || recModelPath == nullptr || charsetPath == nullptr) {
        g_lastError = ENGINE_ERR_NULL;
        return nullptr;
    }

    try {
        auto handle = std::unique_ptr<OcrHandle>(new OcrHandle());
        if (!handle->engine.loadCharsetFromFile(charsetPath)) {
            g_lastError = ENGINE_ERR_NO_MODEL;
            return nullptr;
        }

        auto backend = ocr::createInferenceBackend(detModelPath, recModelPath,
                                                   numThreads > 0 ? numThreads : 4);
        if (!backend) {
            g_lastError = ENGINE_ERR_NO_BACKEND;
            return nullptr;
        }

        handle->engine.setBackend(std::move(backend));
        g_lastError = ENGINE_OK;
        return handle.release();
    } catch (...) {
        g_lastError = ENGINE_ERR_EXCEPTION;
        return nullptr;
    }
}

void engine_ocr_destroy(OcrHandle *h) { delete h; }

int32_t engine_ocr_recognize(OcrHandle *h, const uint8_t *pixels, int32_t width, int32_t height,
                             int32_t stride, int32_t isBgr, engine_ocr_item_cb cb, void *userData,
                             int32_t *outCount) {
    if (h == nullptr) return ENGINE_ERR_NULL;
    if (outCount != nullptr) *outCount = 0;

    try {
        ocr::Image8 image;
        if (!copyImage(pixels, width, height, stride, isBgr, image)) return ENGINE_ERR_BAD_INPUT;

        const ocr::OcrResult result = h->engine.recognize(image);
        int32_t count = 0;
        for (const ocr::OcrItem& item : result.items) {
            ++count;
            if (cb == nullptr) continue;

            engine_ocr_item out{};
            out.x0 = item.box.points[0].x;
            out.y0 = item.box.points[0].y;
            out.x1 = item.box.points[1].x;
            out.y1 = item.box.points[1].y;
            out.x2 = item.box.points[2].x;
            out.y2 = item.box.points[2].y;
            out.x3 = item.box.points[3].x;
            out.y3 = item.box.points[3].y;
            out.confidence = item.confidence;
            out.text = item.text.c_str();
            cb(&out, userData);
        }
        if (outCount != nullptr) *outCount = count;
        return ENGINE_OK;
    } catch (...) {
        return ENGINE_ERR_EXCEPTION;
    }
}

int32_t engine_ocr_recognize_cells(OcrHandle *h, const uint8_t *pixels, int32_t width,
                                   int32_t height, int32_t stride, int32_t isBgr,
                                   const engine_ocr_cell *cells, int32_t cellCount, float scale,
                                   engine_ocr_cell_cb cb, void *userData) {
    if (h == nullptr) return ENGINE_ERR_NULL;
    if (cells == nullptr || cellCount <= 0) return ENGINE_ERR_BAD_INPUT;

    try {
        ocr::Image8 image;
        if (!copyImage(pixels, width, height, stride, isBgr, image)) return ENGINE_ERR_BAD_INPUT;

        std::vector<ocr::Rect> rects;
        rects.reserve(static_cast<size_t>(cellCount));
        for (int32_t i = 0; i < cellCount; ++i) {
            rects.push_back(ocr::Rect{cells[i].left, cells[i].top, cells[i].right, cells[i].bottom});
        }

        const std::vector<ocr::CellReading> readings =
            h->engine.recognizeCells(image, rects, scale);
        if (cb != nullptr) {
            for (size_t i = 0; i < readings.size(); ++i) {
                cb(static_cast<int32_t>(i), readings[i].text.c_str(), readings[i].confidence,
                   readings[i].hasInk ? 1 : 0, userData);
            }
        }
        return ENGINE_OK;
    } catch (...) {
        return ENGINE_ERR_EXCEPTION;
    }
}

}  // extern "C"
