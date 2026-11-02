#pragma once

#include <string>
#include <vector>

#include "table/TableLogic.hpp"

namespace ocr {

/// 裁列结果：裁完的行 + 左边被丢掉的列数（用于对齐置信度矩阵）。
struct TrimmedColumns {
    TableRows rows;
    int droppedLeading = 0;
};

/// 去掉两侧的垃圾列（整列空白、单个线头碎片、右侧背景文字列）。
TrimmedColumns trimColumns(const TableRows& rows);

/// 把「填得不满、且内容像噪声」的整段列清空。
TableRows cleanNoisyColumns(const TableRows& rows);

/// 把「几乎不是数字、也不是中文」的列清空（表头误识别出来的英文串等）。
TableRows cleanBadColumns(const TableRows& rows);

}  // namespace ocr
