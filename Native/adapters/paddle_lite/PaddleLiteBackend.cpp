#include "PaddleLiteBackend.hpp"

#include <cstring>

#ifdef SORT_WITH_PADDLE_LITE

#include <paddle_api.h>
#include <paddle_place.h>

namespace ocr {
namespace {

using paddle::lite_api::CreatePaddlePredictor;
using paddle::lite_api::MobileConfig;
using paddle::lite_api::PaddlePredictor;

bool runPredictor(PaddlePredictor& predictor, const Tensor& input, Tensor& output) {
    auto inputHandle = predictor.GetInput(0);
    if (!inputHandle) return false;

    inputHandle->Resize(input.dims);
    float* buffer = inputHandle->mutable_data<float>();
    if (!buffer) return false;
    std::memcpy(buffer, input.data.data(), input.data.size() * sizeof(float));

    predictor.Run();

    auto outputHandle = predictor.GetOutput(0);
    if (!outputHandle) return false;

    output.dims = outputHandle->shape();
    const float* data = outputHandle->data<float>();
    if (!data) return false;

    size_t count = 1;
    for (int64_t dim : output.dims) count *= static_cast<size_t>(dim);
    output.data.assign(data, data + count);
    return true;
}

}  // namespace

struct PaddleLiteBackend::Impl {
    std::shared_ptr<PaddlePredictor> det;
    std::shared_ptr<PaddlePredictor> rec;
};

PaddleLiteBackend::PaddleLiteBackend() : impl_(std::make_unique<Impl>()) {}

PaddleLiteBackend::~PaddleLiteBackend() = default;

namespace {

std::shared_ptr<PaddlePredictor> loadPredictor(const std::string& path, int numThreads) {
    MobileConfig config;
    config.set_model_from_file(path);
    config.set_threads(numThreads > 0 ? numThreads : 2);
    config.set_power_mode(paddle::lite_api::LITE_POWER_HIGH);
    return CreatePaddlePredictor<MobileConfig>(config);
}

}  // namespace

std::unique_ptr<PaddleLiteBackend> PaddleLiteBackend::create(const std::string& detModelPath,
                                                             const std::string& recModelPath,
                                                             int numThreads) {
    try {
        auto backend = std::unique_ptr<PaddleLiteBackend>(new PaddleLiteBackend());
        backend->impl_->det = loadPredictor(detModelPath, numThreads);
        backend->impl_->rec = loadPredictor(recModelPath, numThreads);
        if (!backend->impl_->det || !backend->impl_->rec) return nullptr;
        return backend;
    } catch (...) {
        return nullptr;
    }
}

bool PaddleLiteBackend::runDet(const Tensor& input, Tensor& output) {
    if (!impl_->det) return false;
    try {
        return runPredictor(*impl_->det, input, output);
    } catch (...) {
        return false;
    }
}

bool PaddleLiteBackend::runRec(const Tensor& input, Tensor& output) {
    if (!impl_->rec) return false;
    try {
        return runPredictor(*impl_->rec, input, output);
    } catch (...) {
        return false;
    }
}

}  // namespace ocr

#else  // 没有 Paddle-Lite 时给空实现，保证上层代码照样能编

namespace ocr {

struct PaddleLiteBackend::Impl {};

PaddleLiteBackend::PaddleLiteBackend() = default;
PaddleLiteBackend::~PaddleLiteBackend() = default;

std::unique_ptr<PaddleLiteBackend> PaddleLiteBackend::create(const std::string&, const std::string&,
                                                             int) {
    return nullptr;
}

bool PaddleLiteBackend::runDet(const Tensor&, Tensor&) { return false; }
bool PaddleLiteBackend::runRec(const Tensor&, Tensor&) { return false; }

}  // namespace ocr

#endif  // SORT_WITH_PADDLE_LITE
