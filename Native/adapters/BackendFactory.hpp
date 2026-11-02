#pragma once

#include <memory>
#include <string>

#include "ocr/Backend.hpp"

namespace ocr {

/// 按编译开关挑一个推理后端：Paddle-Lite 优先，其次 ONNX Runtime，都没有就返回空。
std::unique_ptr<InferenceBackend> createInferenceBackend(const std::string& detModelPath,
                                                         const std::string& recModelPath,
                                                         int numThreads);

}  // namespace ocr
