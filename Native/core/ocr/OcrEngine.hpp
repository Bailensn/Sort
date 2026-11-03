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

    /// 第二主模型（换一代模型当另一路投票），比如主用 v6、第二遍用 v4。
    bool setAlternate(std::unique_ptr<InferenceBackend> backend, std::vector<std::string> charset);
    bool hasAlternate() const { return altBackend_ != nullptr && !altCharset_.empty(); }

    /// 主后端是否允许并发调用；不允许就只能串行跑。
    bool threadSafe() const { return backend_ != nullptr && backend_->threadSafe(); }

    /// 用哪套 rec 模型跑这一批格子。
    enum class CellModel { Primary, Alternate, Referee };

    /// 按外部给定的格子逐个识别；空白格直接跳过，不送模型。
    /// 指定的模型没配置就返回全空。
    std::vector<CellReading> recognizeCells(const Image8& image, const std::vector<Rect>& cells,
                                            float scale = 1.0f,
                                            CellModel model = CellModel::Primary);

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
    std::unique_ptr<InferenceBackend> altBackend_;
    std::vector<std::string> altCharset_;
};

}  // namespace ocr
