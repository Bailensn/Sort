#include "MindSporeBackend.hpp"

#include <cstring>

#ifdef SORT_WITH_MINDSORE_LITE

#include <fstream>
#include <vector>

#include "context.h"
#include "lite_session.h"
#include "model.h"
#include "tensor.h"

namespace ocr {
namespace {

using mindspore::session::LiteSession;

/// 读整个 .ms 文件，Import 需要缓冲区和长度。
bool readAll(const std::string& path, std::vector<char>& out) {
    std::ifstream file(path, std::ios::binary);
    if (!file) return false;
    file.seekg(0, std::ios::end);
    const std::streamsize size = file.tellg();
    if (size <= 0) return false;
    file.seekg(0, std::ios::beg);
    out.resize(static_cast<size_t>(size));
    return static_cast<bool>(file.read(out.data(), size));
}

bool runSession(LiteSession& session, const Tensor& input, Tensor& output) {
    auto inputs = session.GetInputs();
    if (inputs.empty() || inputs[0] == nullptr) return false;

    // rec 的宽度是动态的，跑之前按实际形状改一次。
    std::vector<int> shape(input.dims.begin(), input.dims.end());
    if (!inputs[0]->set_shape(shape)) return false;
    if (inputs[0]->MutableData() == nullptr) return false;

    std::memcpy(inputs[0]->MutableData(), input.data.data(), input.data.size() * sizeof(float));

    if (session.RunGraph() != mindspore::lite::RET_OK) return false;

    auto outputs = session.GetOutputs();
    if (outputs.empty() || outputs[0] == nullptr) return false;

    auto outShape = outputs[0]->shape();
    output.dims.assign(outShape.begin(), outShape.end());

    size_t count = 1;
    for (int dim : outShape) count *= static_cast<size_t>(dim);
    const float* data = outputs[0]->data<float>();
    if (data == nullptr) return false;

    output.data.assign(data, data + count);
    return true;
}

}  // namespace

struct MindSporeBackend::Impl {
    mindspore::lite::Model* detModel = nullptr;
    mindspore::lite::Model* recModel = nullptr;
    std::unique_ptr<LiteSession> det;
    std::unique_ptr<LiteSession> rec;
    std::vector<char> detBuffer;
    std::vector<char> recBuffer;

    ~Impl() {
        det.reset();
        rec.reset();
        if (detModel != nullptr) detModel->Free();
        if (recModel != nullptr) recModel->Free();
    }
};

namespace {

std::unique_ptr<LiteSession> buildSession(mindspore::lite::Model* model, int numThreads) {
    if (model == nullptr) return nullptr;

    mindspore::lite::Context context;
    context.cpu_info_.thread_num_ = numThreads > 0 ? numThreads : 2;
    context.device_list_.clear();
    context.device_list_.push_back({mindspore::lite::DT_CPU, false});

    std::unique_ptr<LiteSession> session(LiteSession::CreateSession(&context));
    if (!session) return nullptr;
    if (session->CompileGraph(model) != mindspore::lite::RET_OK) return nullptr;
    return session;
}

}  // namespace

MindSporeBackend::MindSporeBackend() : impl_(std::make_unique<Impl>()) {}

MindSporeBackend::~MindSporeBackend() = default;

std::unique_ptr<MindSporeBackend> MindSporeBackend::create(const std::string& detModelPath,
                                                           const std::string& recModelPath,
                                                           int numThreads) {
    try {
        auto backend = std::unique_ptr<MindSporeBackend>(new MindSporeBackend());
        auto& impl = *backend->impl_;

        if (!readAll(detModelPath, impl.detBuffer) || !readAll(recModelPath, impl.recBuffer)) {
            return nullptr;
        }
        impl.detModel = mindspore::lite::Model::Import(impl.detBuffer.data(),
                                                       static_cast<int>(impl.detBuffer.size()));
        impl.recModel = mindspore::lite::Model::Import(impl.recBuffer.data(),
                                                       static_cast<int>(impl.recBuffer.size()));
        if (impl.detModel == nullptr || impl.recModel == nullptr) return nullptr;

        impl.det = buildSession(impl.detModel, numThreads);
        impl.rec = buildSession(impl.recModel, numThreads);
        if (!impl.det || !impl.rec) return nullptr;
        return backend;
    } catch (...) {
        return nullptr;
    }
}

bool MindSporeBackend::runDet(const Tensor& input, Tensor& output) {
    if (!impl_->det) return false;
    try {
        return runSession(*impl_->det, input, output);
    } catch (...) {
        return false;
    }
}

bool MindSporeBackend::runRec(const Tensor& input, Tensor& output) {
    if (!impl_->rec) return false;
    try {
        return runSession(*impl_->rec, input, output);
    } catch (...) {
        return false;
    }
}

}  // namespace ocr

#else  // 没有 MindSpore Lite 时给空实现

namespace ocr {

struct MindSporeBackend::Impl {};

MindSporeBackend::MindSporeBackend() = default;
MindSporeBackend::~MindSporeBackend() = default;

std::unique_ptr<MindSporeBackend> MindSporeBackend::create(const std::string&, const std::string&,
                                                           int) {
    return nullptr;
}

bool MindSporeBackend::runDet(const Tensor&, Tensor&) { return false; }
bool MindSporeBackend::runRec(const Tensor&, Tensor&) { return false; }

}  // namespace ocr

#endif  // SORT_WITH_MINDSORE_LITE
