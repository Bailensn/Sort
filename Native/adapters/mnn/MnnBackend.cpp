#include "MnnBackend.hpp"

#include <vector>

#ifdef SORT_WITH_MNN

#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>

namespace ocr {
namespace {

struct Net {
    std::shared_ptr<MNN::Interpreter> interpreter;
    MNN::Session* session = nullptr;
    MNN::Tensor* input = nullptr;
    MNN::Tensor* output = nullptr;
};

std::unique_ptr<Net> openNet(const std::string& path, int numThreads) {
    if (path.empty()) return nullptr;
    auto net = std::make_unique<Net>();
    net->interpreter = std::shared_ptr<MNN::Interpreter>(MNN::Interpreter::createFromFile(path.c_str()),
                                                         MNN::Interpreter::destroy);
    if (!net->interpreter) return nullptr;

    MNN::ScheduleConfig config;
    config.type = MNN_FORWARD_CPU;
    config.numThread = numThreads > 0 ? numThreads : 2;
    net->session = net->interpreter->createSession(config);
    if (net->session == nullptr) return nullptr;

    net->input = net->interpreter->getSessionInput(net->session, nullptr);
    net->output = net->interpreter->getSessionOutput(net->session, nullptr);
    if (net->input == nullptr || net->output == nullptr) return nullptr;
    return net;
}

bool run(Net& net, const Tensor& input, Tensor& output) {
    if (net.session == nullptr || net.input == nullptr || net.output == nullptr) return false;
    if (input.dims.size() != 4) return false;

    std::vector<int> shape(input.dims.begin(), input.dims.end());
    net.interpreter->resizeTensor(net.input, shape);
    net.interpreter->resizeSession(net.session);

    // 主机内存 → 设备内存
    MNN::Tensor hostInput(net.input, MNN::Tensor::CAFFE);
    std::copy(input.data.begin(), input.data.end(), hostInput.host<float>());
    net.input->copyFromHostTensor(&hostInput);

    if (net.interpreter->runSession(net.session) != MNN::NO_ERROR) return false;

    MNN::Tensor hostOutput(net.output, MNN::Tensor::CAFFE);
    net.output->copyToHostTensor(&hostOutput);

    const std::vector<int>& dims = hostOutput.shape();
    const int rank = std::min(4, static_cast<int>(dims.size()));
    output.dims.assign(dims.begin(), dims.begin() + rank);

    const size_t count = hostOutput.elementSize();
    const float* data = hostOutput.host<const float>();
    if (data == nullptr) return false;
    output.data.assign(data, data + count);
    return true;
}

}  // namespace

struct MnnBackend::Impl {
    std::unique_ptr<Net> det;
    std::unique_ptr<Net> rec;
};

MnnBackend::MnnBackend() : impl_(std::make_unique<Impl>()) {}

MnnBackend::~MnnBackend() {
    if (impl_ && impl_->det && impl_->det->interpreter && impl_->det->session) {
        impl_->det->interpreter->releaseSession(impl_->det->session);
    }
    if (impl_ && impl_->rec && impl_->rec->interpreter && impl_->rec->session) {
        impl_->rec->interpreter->releaseSession(impl_->rec->session);
    }
}

std::unique_ptr<MnnBackend> MnnBackend::create(const std::string& detModelPath,
                                               const std::string& recModelPath, int numThreads) {
    auto backend = std::unique_ptr<MnnBackend>(new MnnBackend());
    if (!detModelPath.empty()) {
        backend->impl_->det = openNet(detModelPath, numThreads);
        if (!backend->impl_->det) return nullptr;
    }
    backend->impl_->rec = openNet(recModelPath, numThreads);
    if (!backend->impl_->rec) return nullptr;
    return backend;
}

bool MnnBackend::runDet(const Tensor& input, Tensor& output) {
    if (!impl_->det) return false;
    return run(*impl_->det, input, output);
}

bool MnnBackend::runRec(const Tensor& input, Tensor& output) {
    if (!impl_->rec) return false;
    return run(*impl_->rec, input, output);
}

}  // namespace ocr

#else

namespace ocr {

struct MnnBackend::Impl {};

MnnBackend::MnnBackend() = default;
MnnBackend::~MnnBackend() = default;

std::unique_ptr<MnnBackend> MnnBackend::create(const std::string&, const std::string&, int) {
    return nullptr;
}

bool MnnBackend::runDet(const Tensor&, Tensor&) { return false; }
bool MnnBackend::runRec(const Tensor&, Tensor&) { return false; }

}  // namespace ocr

#endif  // SORT_WITH_MNN
