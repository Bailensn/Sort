#pragma once

#include <string>

namespace ocr {

enum class LimitType { Max, Min, ResizeLong };

/// 参数与 Android 侧 PaddleOCRConfig 一一对应，默认值必须一致。
struct OcrConfig {
    int detLimitSideLen = 64;
    LimitType detLimitType = LimitType::Min;
    int detMaxSideLimit = 4000;

    float detThresh = 0.3f;
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
