#pragma once

#include <memory>
#include <string>

#include "ocr/Backend.hpp"

namespace ocr {

/// MindSpore Lite 的 C++ 实现；模型是 .ms（converter_lite 从 .onnx 转出来）。
/// 只有定义了 SORT_WITH_MINDSORE_LITE 才会编出实体。
class MindSporeBackend : public InferenceBackend {
public:
    static std::unique_ptr<MindSporeBackend> create(const std::string& detModelPath,
                                                    const std::string& recModelPath,
                                                    int numThreads);

    ~MindSporeBackend() override;

    bool runDet(const Tensor& input, Tensor& output) override;
    bool runRec(const Tensor& input, Tensor& output) override;

    MindSporeBackend(const MindSporeBackend&) = delete;
    MindSporeBackend& operator=(const MindSporeBackend&) = delete;

private:
    MindSporeBackend();
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace ocr
