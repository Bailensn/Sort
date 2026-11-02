#pragma once

#include <memory>
#include <string>

#include "ocr/Backend.hpp"

namespace ocr {

/// Paddle-Lite 的 C++ 实现；模型是 .nb（opt 工具转出来的 NaiveBuffer）。
/// 只有定义了 SORT_WITH_PADDLE_LITE 才会编出实体。
class PaddleLiteBackend : public InferenceBackend {
public:
    static std::unique_ptr<PaddleLiteBackend> create(const std::string& detModelPath,
                                                     const std::string& recModelPath,
                                                     int numThreads);

    ~PaddleLiteBackend() override;

    bool runDet(const Tensor& input, Tensor& output) override;
    bool runRec(const Tensor& input, Tensor& output) override;

    PaddleLiteBackend(const PaddleLiteBackend&) = delete;
    PaddleLiteBackend& operator=(const PaddleLiteBackend&) = delete;

private:
    PaddleLiteBackend();
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace ocr
