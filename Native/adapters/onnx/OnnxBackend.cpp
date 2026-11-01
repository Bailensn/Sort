#include "OnnxBackend.hpp"

#ifdef SORT_WITH_ONNXRUNTIME

#include <array>
#include <cstring>

#include <onnxruntime_cxx_api.h>

namespace ocr {
namespace {

/// 同一个 env 复用到所有会话，ORT 要求进程内单例。
Ort::Env& sharedEnv() {
    static Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "sort-ocr");
    return env;
}

struct Session {
    Ort::Session session{nullptr};
    std::string inputName;
    std::string outputName;
    std::vector<const char*> inputNames;
    std::vector<const char*> outputNames;
};

bool openSession(Session& out, const std::string& path, int numThreads) {
    Ort::SessionOptions options;
    options.SetIntraOpNumThreads(numThreads);
    options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

    out.session = Ort::Session(sharedEnv(), path.c_str(), options);

    Ort::AllocatorWithDefaultOptions allocator;
    out.inputName = out.session.GetInputNameAllocated(0, allocator).get();
    out.outputName = out.session.GetOutputNameAllocated(0, allocator).get();
    out.inputNames = {out.inputName.c_str()};
    out.outputNames = {out.outputName.c_str()};
    return true;
}

bool runSession(Session& session, const Tensor& input, Tensor& output) {
    if (input.dims.size() != 4) return false;

    Ort::MemoryInfo memory =
        Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    const std::array<int64_t, 4> shape = {input.dims[0], input.dims[1], input.dims[2],
                                          input.dims[3]};
    Ort::Value tensor = Ort::Value::CreateTensor<float>(
        memory, const_cast<float*>(input.data.data()), input.data.size(), shape.data(),
        shape.size());

    auto outputs = session.session.Run(Ort::RunOptions{nullptr}, session.inputNames.data(), &tensor,
                                       1, session.outputNames.data(), 1);
    if (outputs.empty()) return false;

    const auto info = outputs[0].GetTensorTypeAndShapeInfo();
    output.dims = info.GetShape();
    const float* data = outputs[0].GetTensorData<float>();
    const size_t count = info.GetElementCount();
    output.data.assign(data, data + count);
    return true;
}

}  // namespace

struct OnnxBackend::Impl {
    Session det;
    Session rec;
};

OnnxBackend::OnnxBackend() : impl_(std::make_unique<Impl>()) {}

OnnxBackend::~OnnxBackend() = default;

std::unique_ptr<OnnxBackend> OnnxBackend::create(const std::string& detModelPath,
                                                 const std::string& recModelPath,
                                                 int numThreads) {
    auto backend = std::unique_ptr<OnnxBackend>(new OnnxBackend());
    if (!openSession(backend->impl_->det, detModelPath, numThreads)) return nullptr;
    if (!openSession(backend->impl_->rec, recModelPath, numThreads)) return nullptr;
    return backend;
}

bool OnnxBackend::runDet(const Tensor& input, Tensor& output) {
    return runSession(impl_->det, input, output);
}

bool OnnxBackend::runRec(const Tensor& input, Tensor& output) {
    return runSession(impl_->rec, input, output);
}

}  // namespace ocr

#else  // 没有 ORT 时给一套空实现，保证上层代码照样能编过

namespace ocr {

struct OnnxBackend::Impl {};

OnnxBackend::OnnxBackend() = default;
OnnxBackend::~OnnxBackend() = default;

std::unique_ptr<OnnxBackend> OnnxBackend::create(const std::string&, const std::string&, int) {
    return nullptr;
}

bool OnnxBackend::runDet(const Tensor&, Tensor&) { return false; }
bool OnnxBackend::runRec(const Tensor&, Tensor&) { return false; }

}  // namespace ocr

#endif  // SORT_WITH_ONNXRUNTIME
