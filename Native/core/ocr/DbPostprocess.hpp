#pragma once

#include <vector>

#include "Config.hpp"
#include "Types.hpp"

namespace ocr {

/// DB 后处理：阈值 → 找外轮廓 → 最小外接矩形 → 打分 → unclip → 缩放回原图。
/// 只追踪外轮廓（OpenCV 的 RETR_LIST 还会返回孔洞），孔洞在文字笔画里极小，
/// 过不了 min_size 与分数两道筛，这里省掉。
std::vector<Box> dbPostprocess(const Tensor& prediction, int originalWidth, int originalHeight,
                               const OcrConfig& config);

}  // namespace ocr
