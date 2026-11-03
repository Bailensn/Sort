#pragma once

#include <memory>
#include <string>
#include <vector>

#include "ocr/Backend.hpp"

namespace ocr {

/// ONNX Runtime 的 C++ 实现，Android / iOS 共用；Harmony 暂不支持。
/// 只有定义了 SORT_WITH_ONNXRUNTIME 才会编出实体。
class OnnxBackend : public InferenceBackend {
public:
    static std::unique_ptr<OnnxBackend> create(const std::string& detModelPath,
                                               const std::string& recModelPath, int numThreads);

    ~OnnxBackend() override;

    bool runDet(const Tensor& input, Tensor& output) override;
    bool runRec(const Tensor& input, Tensor& output) override;

    /// ORT 的 Session::Run 是线程安全的，整图回读因此可以和逐格识别并行。
    bool threadSafe() const override { return true; }

    OnnxBackend(const OnnxBackend&) = delete;
    OnnxBackend& operator=(const OnnxBackend&) = delete;

private:
    OnnxBackend();
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace ocr
