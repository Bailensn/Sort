#pragma once

#include "Types.hpp"

namespace ocr {

/// 纯虚的推理后端：核心只认张量，ORT/JNI 实现放在各端。
class InferenceBackend {
public:
    virtual ~InferenceBackend() = default;

    /// 输入 [1,3,H,W] 的 det 张量，输出 [1,1,H,W] 的概率图。
    virtual bool runDet(const Tensor& input, Tensor& output) = 0;

    /// 输入 [N,3,48,W] 的 rec 张量，输出 [N,T,C] 的 logits。
    virtual bool runRec(const Tensor& input, Tensor& output) = 0;

    /// 同一个后端能否被两个线程同时调用；只有确认过的后端才允许并行。
    virtual bool threadSafe() const { return false; }
};

}  // namespace ocr
