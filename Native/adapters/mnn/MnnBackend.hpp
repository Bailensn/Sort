#pragma once

#include <memory>
#include <string>

#include "ocr/Backend.hpp"

namespace ocr {

/// MNN 的 C++ 实现；模型是 .mnn（MNNConvert 从 .onnx 转出来）。
/// 鸿蒙走这条，安卓/iOS 仍用 ONNX Runtime。det 路径为空表示只建 rec（复核模型）。
class MnnBackend : public InferenceBackend {
public:
    static std::unique_ptr<MnnBackend> create(const std::string& detModelPath,
                                              const std::string& recModelPath, int numThreads);

    ~MnnBackend() override;

    bool runDet(const Tensor& input, Tensor& output) override;
    bool runRec(const Tensor& input, Tensor& output) override;

    MnnBackend(const MnnBackend&) = delete;
    MnnBackend& operator=(const MnnBackend&) = delete;

private:
    MnnBackend();
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace ocr
