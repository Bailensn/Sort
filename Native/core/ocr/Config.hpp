#pragma once

#include <string>

namespace ocr {

enum class LimitType { Max, Min, ResizeLong };

/// 参数沿用 PaddleOCR 官方默认：检测长边缩到 960，识别再按原图裁框。
struct OcrConfig {
    int detLimitSideLen = 960;
    LimitType detLimitType = LimitType::Max;
    int detMaxSideLimit = 4000;

    float detThresh = 0.45f;
    float detBoxThresh = 0.6f;
    float detUnclipRatio = 1.5f;
    int detMaxCandidates = 3000;
    bool detUseDilation = false;
    bool detScoreSlow = false;  // false = fast，按四点算分
    bool detBoxTypeQuad = true;

    float recScoreThresh = 0.0f;
    int recBatchSize = 1;
};

}  // namespace ocr
