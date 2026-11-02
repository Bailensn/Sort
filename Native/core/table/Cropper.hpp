#pragma once

#include <optional>

#include "ocr/Types.hpp"

namespace ocr {

/// 找有内容的那块边界：大片桌面、黑边、灰底在这里被排除。
/// 判不出来或者裁得太狠/太没裁时返回空，调用方直接用原图。
std::optional<Rect> contentBounds(const Image8& src);

}  // namespace ocr
