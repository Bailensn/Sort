#include "engine_ocr_capi.h"

#include "ocr/Charset.hpp"

#include <cstdlib>
#include <cstring>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#include "BackendFactory.hpp"
#include "ocr/OcrEngine.hpp"
#include "table/TableAnalyzer.hpp"

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

namespace {

/// 把一张 Image8 交出去，所有权转给调用方。
void moveImage(ocr::Image8&& source, engine_table_image *out) {
    if (out == nullptr) return;
    out->width = source.width;
    out->height = source.height;
    const size_t bytes = source.data.size();
    if (bytes == 0) {
        out->data = nullptr;
        return;
    }
    out->data = static_cast<uint8_t *>(std::malloc(bytes));
    if (out->data == nullptr) {
        out->width = 0;
        out->height = 0;
        return;
    }
    std::memcpy(out->data, source.data.data(), bytes);
}

int32_t noteKind(ocr::Note::Kind kind) {
    switch (kind) {
        case ocr::Note::Kind::SequenceBreak: return ENGINE_NOTE_SEQUENCE_BREAK;
        case ocr::Note::Kind::SequenceOk: return ENGINE_NOTE_SEQUENCE_OK;
        case ocr::Note::Kind::IdBreak: return ENGINE_NOTE_ID_BREAK;
        case ocr::Note::Kind::SumChecked: return ENGINE_NOTE_SUM_CHECKED;
        case ocr::Note::Kind::PartialTotals: return ENGINE_NOTE_PARTIAL_TOTALS;
    }
    return ENGINE_NOTE_SEQUENCE_OK;
}

}  // namespace

int32_t engine_table_analyze(OcrHandle *h, const uint8_t *pixels, int32_t width, int32_t height,
                             int32_t stride, int32_t isBgr, engine_table_row_cb onRow,
                             engine_table_header_cb onHeader, engine_table_note_cb onNote,
                             engine_table_index_cb onSuspiciousRow, engine_table_stats *outStats,
                             engine_table_image *outImage, engine_table_image *outOverlay,
                             engine_table_image *outEnhanced) {
    if (h == nullptr) return ENGINE_ERR_NULL;
    if (outStats != nullptr) *outStats = engine_table_stats{};
    if (outImage != nullptr) *outImage = engine_table_image{};
    if (outOverlay != nullptr) *outOverlay = engine_table_image{};
    if (outEnhanced != nullptr) *outEnhanced = engine_table_image{};

    try {
        ocr::Image8 image;
        if (!copyImage(pixels, width, height, stride, isBgr, image)) {
            g_lastError = ENGINE_ERR_BAD_INPUT;
            return ENGINE_ERR_BAD_INPUT;
        }

        ocr::TableAnalysis analysis = ocr::analyzeTable(h->engine, image);

        if (onHeader != nullptr) {
            for (const auto& column : analysis.header.columns) {
                const int32_t role = static_cast<int32_t>(column.role);
                onHeader(column.index, column.name.c_str(), role, nullptr);
            }
        }
        if (onRow != nullptr) {
            for (size_t r = 0; r < analysis.rows.size(); ++r) {
                const auto& row = analysis.rows[r];
                std::vector<const char*> cells;
                cells.reserve(row.size());
                for (const auto& cell : row) cells.push_back(cell.c_str());
                engine_table_row payload{};
                payload.index = static_cast<int32_t>(r);
                payload.columnCount = static_cast<int32_t>(cells.size());
                payload.cells = cells.empty() ? nullptr : cells.data();
                onRow(&payload, nullptr);
            }
        }
        if (onNote != nullptr) {
            for (const auto& note : analysis.report.notes) onNote(noteKind(note.kind), note.a, note.b, nullptr);
        }
        if (onSuspiciousRow != nullptr) {
            for (int row : analysis.report.suspiciousRows) onSuspiciousRow(row, nullptr);
        }
        if (outStats != nullptr) {
            const ocr::TableStats& stats = analysis.stats;
            outStats->byLines = stats.byLines ? 1 : 0;
            outStats->rectified = stats.rectified ? 1 : 0;
            outStats->horizontalLines = stats.horizontalLines;
            outStats->verticalLines = stats.verticalLines;
            outStats->cellCount = stats.cellCount;
            outStats->correctedCells = stats.correctedCells;
            outStats->suspiciousRows = static_cast<int32_t>(analysis.report.suspiciousRows.size());
            outStats->lowConfidenceCells = stats.lowConfidenceCells;
            outStats->headerFromImage = analysis.header.fromImage ? 1 : 0;
            outStats->averageConfidence = stats.averageConfidence;
            outStats->confidenceMin = stats.confidenceMin;
            outStats->confidenceMax = stats.confidenceMax;
            outStats->deskewDegrees = static_cast<float>(stats.deskewDegrees);
            outStats->elapsedMs = stats.elapsedMs;
            outStats->cellMs = stats.cellMs;
            outStats->detectMs = stats.detectMs;
        }
        moveImage(std::move(analysis.working), outImage);
        moveImage(std::move(analysis.overlay), outOverlay);
        moveImage(std::move(analysis.enhanced), outEnhanced);
        g_lastError = ENGINE_OK;
        return ENGINE_OK;
    } catch (...) {
        g_lastError = ENGINE_ERR_EXCEPTION;
        return ENGINE_ERR_EXCEPTION;
    }
}

namespace {

void toRows(const engine_table_matrix *matrix, ocr::TableRows &rows) {
    rows.clear();
    if (matrix == nullptr || matrix->rowCount <= 0 || matrix->columnCounts == nullptr) return;
    rows.reserve(static_cast<size_t>(matrix->rowCount));
    size_t offset = 0;
    for (int32_t r = 0; r < matrix->rowCount; ++r) {
        const int32_t columns = matrix->columnCounts[r];
        std::vector<std::string> row;
        row.reserve(static_cast<size_t>(columns));
        for (int32_t c = 0; c < columns; ++c) {
            const char *cell = matrix->cells != nullptr ? matrix->cells[offset++] : nullptr;
            row.emplace_back(cell != nullptr ? cell : "");
        }
        rows.push_back(std::move(row));
    }
}

void emitRows(const ocr::TableRows &rows, engine_table_row_cb onRow) {
    if (onRow == nullptr) return;
    for (size_t r = 0; r < rows.size(); ++r) {
        std::vector<const char *> cells;
        cells.reserve(rows[r].size());
        for (const auto &cell : rows[r]) cells.push_back(cell.c_str());
        engine_table_row payload{};
        payload.index = static_cast<int32_t>(r);
        payload.columnCount = static_cast<int32_t>(cells.size());
        payload.cells = cells.empty() ? nullptr : cells.data();
        onRow(&payload, nullptr);
    }
}

}  // namespace

int32_t engine_table_detect_header(const engine_table_matrix *rows,
                                   engine_table_header_cb onHeader, int32_t *outFromImage) {
    if (outFromImage != nullptr) *outFromImage = 0;
    try {
        ocr::TableRows input;
        toRows(rows, input);
        if (input.empty()) return ENGINE_ERR_BAD_INPUT;

        const ocr::TableHeader header = ocr::detectHeader(input);
        if (onHeader != nullptr) {
            for (const auto &column : header.columns) {
                onHeader(column.index, column.name.c_str(), static_cast<int32_t>(column.role), nullptr);
            }
        }
        if (outFromImage != nullptr) *outFromImage = header.fromImage ? 1 : 0;
        return ENGINE_OK;
    } catch (...) {
        return ENGINE_ERR_EXCEPTION;
    }
}

int32_t engine_table_self_check(const engine_table_matrix *rows, const float *confidences,
                                int32_t totalColumn, const int32_t *subjectColumns,
                                int32_t subjectCount, engine_table_row_cb onRow,
                                engine_table_note_cb onNote, engine_table_index_cb onSuspiciousRow,
                                int32_t *outCorrected, int32_t *outSuspicious) {
    if (outCorrected != nullptr) *outCorrected = 0;
    if (outSuspicious != nullptr) *outSuspicious = 0;
    try {
        ocr::TableRows input;
        toRows(rows, input);
        if (input.empty()) return ENGINE_ERR_BAD_INPUT;

        ocr::TableHeader header;
        if (totalColumn >= 0) {
            ocr::ColumnSpec total;
            total.index = totalColumn;
            total.role = ocr::ColumnRole::Total;
            header.columns.push_back(total);
            for (int32_t i = 0; i < subjectCount; ++i) {
                ocr::ColumnSpec subject;
                subject.index = subjectColumns[i];
                subject.role = ocr::ColumnRole::Subject;
                header.columns.push_back(subject);
            }
        }

        std::vector<std::vector<float>> confidenceRows;
        if (confidences != nullptr && !input.empty()) {
            confidenceRows.assign(input.size(), std::vector<float>(input.front().size(), 0.0f));
            size_t offset = 0;
            for (auto &row : confidenceRows) {
                for (auto &value : row) value = confidences[offset++];
            }
        }

        const ocr::CheckResult checked =
            ocr::selfCheck(input, confidenceRows.empty() ? nullptr : &confidenceRows,
                           header.columns.empty() ? nullptr : &header);
        emitRows(checked.rows, onRow);
        if (onNote != nullptr) {
            for (const auto &note : checked.report.notes) {
                onNote(noteKind(note.kind), note.a, note.b, nullptr);
            }
        }
        if (onSuspiciousRow != nullptr) {
            for (int row : checked.report.suspiciousRows) onSuspiciousRow(row, nullptr);
        }
        if (outCorrected != nullptr) *outCorrected = checked.report.correctedCells;
        if (outSuspicious != nullptr) {
            *outSuspicious = static_cast<int32_t>(checked.report.suspiciousRows.size());
        }
        return ENGINE_OK;
    } catch (...) {
        return ENGINE_ERR_EXCEPTION;
    }
}

void engine_table_image_free(engine_table_image *image) {
    if (image == nullptr) return;
    std::free(image->data);
    image->data = nullptr;
    image->width = 0;
    image->height = 0;
}

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

int32_t engine_ocr_set_referee(OcrHandle *h, const char *recModelPath, const char *charsetPath) {
    if (h == nullptr || recModelPath == nullptr || charsetPath == nullptr) return ENGINE_ERR_NULL;

    try {
        std::vector<std::string> charset;
        if (!ocr::loadCharset(charsetPath, charset) || charset.empty()) return ENGINE_ERR_NO_MODEL;

        auto backend = ocr::createRefereeBackend(recModelPath, 2);
        if (!backend) return ENGINE_ERR_NO_BACKEND;
        if (!h->engine.setReferee(std::move(backend), std::move(charset))) {
            return ENGINE_ERR_NO_BACKEND;
        }
        return ENGINE_OK;
    } catch (...) {
        return ENGINE_ERR_EXCEPTION;
    }
}

namespace {

int32_t recognizeCellsImpl(OcrHandle *h, const uint8_t *pixels, int32_t width, int32_t height,
                           int32_t stride, int32_t isBgr, const engine_ocr_cell *cells,
                           int32_t cellCount, float scale, engine_ocr_cell_cb cb, void *userData,
                           bool referee) {
    if (h == nullptr) return ENGINE_ERR_NULL;
    if (cells == nullptr || cellCount <= 0) return ENGINE_ERR_BAD_INPUT;
    if (referee && !h->engine.hasReferee()) return ENGINE_ERR_NO_BACKEND;

    try {
        ocr::Image8 image;
        if (!copyImage(pixels, width, height, stride, isBgr, image)) return ENGINE_ERR_BAD_INPUT;

        std::vector<ocr::Rect> rects;
        rects.reserve(static_cast<size_t>(cellCount));
        for (int32_t i = 0; i < cellCount; ++i) {
            rects.push_back(ocr::Rect{cells[i].left, cells[i].top, cells[i].right, cells[i].bottom});
        }

        const std::vector<ocr::CellReading> readings =
            h->engine.recognizeCells(image, rects, scale, referee);
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

}  // namespace

int32_t engine_ocr_recognize_cells(OcrHandle *h, const uint8_t *pixels, int32_t width,
                                   int32_t height, int32_t stride, int32_t isBgr,
                                   const engine_ocr_cell *cells, int32_t cellCount, float scale,
                                   engine_ocr_cell_cb cb, void *userData) {
    return recognizeCellsImpl(h, pixels, width, height, stride, isBgr, cells, cellCount, scale, cb,
                              userData, false);
}

int32_t engine_ocr_recognize_cells_ref(OcrHandle *h, const uint8_t *pixels, int32_t width,
                                       int32_t height, int32_t stride, int32_t isBgr,
                                       const engine_ocr_cell *cells, int32_t cellCount, float scale,
                                       engine_ocr_cell_cb cb, void *userData) {
    return recognizeCellsImpl(h, pixels, width, height, stride, isBgr, cells, cellCount, scale, cb,
                              userData, true);
}

}  // extern "C"
