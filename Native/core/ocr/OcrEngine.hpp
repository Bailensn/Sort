#pragma once

#include <memory>
#include <string>
#include <vector>

#include "Backend.hpp"
#include "Config.hpp"
#include "Types.hpp"

namespace ocr {

struct OcrItem {
    Box box;
    std::string text;
    float confidence = 0.0f;
};

struct OcrResult {
    std::vector<OcrItem> items;
    double detectionMs = 0.0;
    double recognitionMs = 0.0;
    double totalMs = 0.0;
};

struct CellReading {
    std::string text;
    float confidence = 0.0f;
    bool hasInk = false;
};

/// 整条流水线：det → 排序 → 逐框裁图 → rec 批处理 → 解码。
class OcrEngine {
public:
    explicit OcrEngine(OcrConfig config = OcrConfig{});

    void setBackend(std::unique_ptr<InferenceBackend> backend);
    bool setCharset(std::vector<std::string> charset);
    bool loadCharsetFromFile(const std::string& path);

    OcrResult recognize(const Image8& image);

    /// 复核模型：第二套 rec 后端与字符表，只用来给有争议的格子投票。
    bool setReferee(std::unique_ptr<InferenceBackend> backend, std::vector<std::string> charset);
    bool hasReferee() const { return refereeBackend_ != nullptr && !refereeCharset_.empty(); }

    /// 按外部给定的格子逐个识别；空白格直接跳过，不送模型。
    /// referee = true 时走复核模型，没有配置复核模型就返回全空。
    std::vector<CellReading> recognizeCells(const Image8& image, const std::vector<Rect>& cells,
                                            float scale = 1.0f, bool referee = false);

private:
    std::vector<OcrItem> recognizeBoxes(const Image8& image, const std::vector<Box>& boxes,
                                        double& recognitionMs);
    void runCellBatch(const std::vector<int>& indices, const std::vector<Image8>& crops,
                      std::vector<CellReading>& output, InferenceBackend* backend,
                      const std::vector<std::string>& charset);

    OcrConfig config_;
    std::vector<std::string> charset_;
    std::unique_ptr<InferenceBackend> backend_;
    std::unique_ptr<InferenceBackend> refereeBackend_;
    std::vector<std::string> refereeCharset_;
};

}  // namespace ocr
