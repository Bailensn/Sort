#include "MindSporeBackend.hpp"

#include <cstring>
#include <vector>

#ifdef SORT_WITH_MINDSORE_LITE

#include "mindspore/context.h"
#include "mindspore/model.h"
#include "mindspore/status.h"
#include "mindspore/tensor.h"
#include "mindspore/types.h"

namespace ocr {
namespace {

OH_AI_ModelHandle loadModel(const std::string& path, const OH_AI_ContextHandle context) {
    OH_AI_ModelHandle model = OH_AI_ModelCreate();
    if (model == nullptr) return nullptr;
    // .ms 保留动态维度，构建时不给 inputShape，形状在 Predict 前再定
    if (OH_AI_ModelBuildFromFile(model, path.c_str(), OH_AI_MODELTYPE_MINDIR, context) !=
        OH_AI_STATUS_SUCCESS) {
        OH_AI_ModelDestroy(&model);
        return nullptr;
    }
    return model;
}

bool runModel(const OH_AI_ModelHandle model, const Tensor& input, Tensor& output) {
    if (model == nullptr || input.dims.size() != 4) return false;

    const std::vector<int64_t> shape(input.dims.begin(), input.dims.end());
    OH_AI_TensorHandleArray inputs = OH_AI_ModelGetInputs(model);
    if (inputs.handle_num < 1 || inputs.handle_list == nullptr) return false;

    // rec 的宽度每张图都不同，必须走 Resize 才会重新分配输入缓冲
    OH_AI_ShapeInfo info{};
    info.shape_num = shape.size();
    std::memcpy(info.shape, shape.data(), shape.size() * sizeof(int64_t));
    if (OH_AI_ModelResize(model, inputs, &info, 1) == OH_AI_STATUS_SUCCESS) {
        inputs = OH_AI_ModelGetInputs(model);
        if (inputs.handle_num < 1 || inputs.handle_list == nullptr) return false;
    }

    const size_t bytes = input.data.size() * sizeof(float);
    if (OH_AI_TensorGetDataSize(inputs.handle_list[0]) < bytes) return false;
    std::memcpy(OH_AI_TensorGetMutableData(inputs.handle_list[0]), input.data.data(), bytes);

    OH_AI_TensorHandleArray outputs;
    std::memset(&outputs, 0, sizeof(outputs));
    if (OH_AI_ModelPredict(model, inputs, &outputs, nullptr, nullptr) != OH_AI_STATUS_SUCCESS) {
        return false;
    }
    if (outputs.handle_num < 1 || outputs.handle_list == nullptr) return false;

    size_t rank = 0;
    const int64_t* outShape = OH_AI_TensorGetShape(outputs.handle_list[0], &rank);
    if (outShape == nullptr || rank == 0) return false;

    output.dims.assign(outShape, outShape + rank);
    size_t count = 1;
    for (int dim : output.dims) count *= static_cast<size_t>(dim);

    const float* data = static_cast<const float*>(OH_AI_TensorGetData(outputs.handle_list[0]));
    if (data == nullptr) return false;
    output.data.assign(data, data + count);
    return true;
}

}  // namespace

struct MindSporeBackend::Impl {
    OH_AI_ContextHandle context = nullptr;
    OH_AI_ModelHandle det = nullptr;
    OH_AI_ModelHandle rec = nullptr;

    ~Impl() {
        if (det != nullptr) OH_AI_ModelDestroy(&det);
        if (rec != nullptr) OH_AI_ModelDestroy(&rec);
        if (context != nullptr) OH_AI_ContextDestroy(&context);
    }
};

MindSporeBackend::MindSporeBackend() : impl_(std::make_unique<Impl>()) {}

MindSporeBackend::~MindSporeBackend() = default;

std::unique_ptr<MindSporeBackend> MindSporeBackend::create(const std::string& detModelPath,
                                                           const std::string& recModelPath,
                                                           int numThreads) {
    auto backend = std::unique_ptr<MindSporeBackend>(new MindSporeBackend());
    auto& impl = *backend->impl_;

    impl.context = OH_AI_ContextCreate();
    if (impl.context == nullptr) return nullptr;
    OH_AI_ContextSetThreadNum(impl.context, numThreads > 0 ? numThreads : 2);
    // 不显式加设备，MindSpore Lite 会以「Device info list is empty!」拒绝建模型
    OH_AI_DeviceInfoHandle device = OH_AI_DeviceInfoCreate(OH_AI_DEVICETYPE_CPU);
    if (device == nullptr) return nullptr;
    OH_AI_ContextAddDeviceInfo(impl.context, device);

    impl.det = loadModel(detModelPath, impl.context);
    impl.rec = loadModel(recModelPath, impl.context);
    if (impl.det == nullptr || impl.rec == nullptr) return nullptr;
    return backend;
}

bool MindSporeBackend::runDet(const Tensor& input, Tensor& output) {
    return runModel(impl_->det, input, output);
}

bool MindSporeBackend::runRec(const Tensor& input, Tensor& output) {
    return runModel(impl_->rec, input, output);
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
