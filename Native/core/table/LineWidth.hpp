#pragma once

#include "ocr/Types.hpp"
#include "table/GridBuilder.hpp"

namespace ocr {

/// 估表格线有多粗；裁格时按它往内缩，线头才不会残留成「1」。
int estimateLineWidth(const Image8& src, const GridLines& lines);

}  // namespace ocr
