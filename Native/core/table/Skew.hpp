#pragma once

#include "ocr/Types.hpp"

namespace ocr {

struct SkewResult {
    Image8 image;
    double degrees = 0.0;
};

/// 估倾斜角并转正：投影法的峰最锐时的角度就是纸张的歪角。
/// 角度小于 0.2 度时原图返回，免得白跑一次重采样。
SkewResult deskewImage(const Image8& src);

/// 只估角度不转图；单格用更大的 maxAngle 和更严的 minGain。
double estimateSkewDegrees(const Image8& src, double maxAngle, double step, double minGain = 1.02);

}  // namespace ocr
