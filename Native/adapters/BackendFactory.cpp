#include "BackendFactory.hpp"

#ifdef SORT_WITH_MINDSORE_LITE
#include "mindspore/MindSporeBackend.hpp"
#elif defined(SORT_WITH_PADDLE_LITE)
#include "paddle_lite/PaddleLiteBackend.hpp"
#elif defined(SORT_WITH_ONNXRUNTIME)
#include "onnx/OnnxBackend.hpp"
#endif

namespace ocr {

std::unique_ptr<InferenceBackend> createRefereeBackend(const std::string& recModelPath,
                                                       int numThreads) {
#if defined(SORT_WITH_ONNXRUNTIME)
    // 只有 ORT 支持只建 rec；MindSpore / Paddle-Lite 这边没有复核模型
    return OnnxBackend::create(std::string(), recModelPath, numThreads);
#else
    (void)recModelPath;
    (void)numThreads;
    return nullptr;
#endif
}

std::unique_ptr<InferenceBackend> createInferenceBackend(const std::string& detModelPath,
                                                         const std::string& recModelPath,
                                                         int numThreads) {
#if defined(SORT_WITH_MINDSORE_LITE)
    return MindSporeBackend::create(detModelPath, recModelPath, numThreads);
#elif defined(SORT_WITH_PADDLE_LITE)
    return PaddleLiteBackend::create(detModelPath, recModelPath, numThreads);
#elif defined(SORT_WITH_ONNXRUNTIME)
    return OnnxBackend::create(detModelPath, recModelPath, numThreads);
#else
    (void)detModelPath;
    (void)recModelPath;
    (void)numThreads;
    return nullptr;
#endif
}

}  // namespace ocr
