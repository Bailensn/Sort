#pragma once

#include <string>
#include <vector>

#include "Config.hpp"
#include "ImageOps.hpp"
#include "Types.hpp"

namespace ocr {

struct DetPreprocessResult {
    Tensor tensor;
    int originalWidth = 0;
    int originalHeight = 0;
};

struct RecPreprocessResult {
    Tensor tensor;
    int batch = 0;
    int height = 0;
    int width = 0;
};

/// 缩到 32 的倍数，顺序与 Android 的 DetPreprocessor 完全一致。
DetPreprocessResult preprocessDet(const Image8& src, const OcrConfig& config);

/// 每张裁图各自缩到高 48，右侧补 0 到同宽后拼成 NCHW。
RecPreprocessResult preprocessRec(const std::vector<Image8>& crops);

/// 同一件事，但只借用指针，避免成批时把裁图再拷一遍。
RecPreprocessResult preprocessRec(const std::vector<const Image8*>& crops);

}  // namespace ocr
