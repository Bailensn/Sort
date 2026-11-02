// 主机（Linux x86_64）上验证 .ms 模型用的垫片。
//
// 鸿蒙的 MindSpore Lite 只暴露 OH_AI_* C API，官方 Linux 包暴露的是同签名的 MS_*。
// 这里把 OH_AI_* 转给 MS_*，于是同一份 MindSporeBackend.cpp 能在 CI 上用真模型跑通，
// 不用等华为真机。只在 SORT_MSLITE_HOST_TEST 打开时参与编译。

#include <cstring>
#include <vector>

// 上游包的头部路径自带 include/ 前缀，别改
#include "mindspore/context.h"
#include "mindspore/model.h"
#include "mindspore/status.h"
#include "mindspore/tensor.h"
#include "mindspore/types.h"

// 上游头和 OH 头用的是同一组 include guard（连 tensor 的拼写错误都一样），
// 先清掉才能把两套声明放进同一个编译单元
#undef MINDSPORE_INCLUDE_C_API_CONTEXT_C_H
#undef MINDSPORE_INCLUDE_C_API_DATA_TYPE_C_H
#undef MINDSPORE_INCLUDE_C_API_FORMAT_C_H
#undef MINDSPORE_INCLUDE_C_API_MODEL_C_H
#undef MINDSPORE_INCLUDE_C_API_STATUS_C_H
#undef MINDSPORE_INCLUDE_C_API_TENSOE_C_H
#undef MINDSPORE_INCLUDE_C_API_TYPES_C_H

// 上游包的头部路径自带 include/ 前缀，别改
#include "include/c_api/context_c.h"
#include "include/c_api/model_c.h"
#include "include/c_api/tensor_c.h"

namespace {

MSTensorHandleArray toMs(const OH_AI_TensorHandleArray& array) {
    return MSTensorHandleArray{array.handle_num, array.handle_list};
}

OH_AI_TensorHandleArray toOh(const MSTensorHandleArray& array) {
    return OH_AI_TensorHandleArray{array.handle_num, array.handle_list};
}

}  // namespace

extern "C" {

OH_AI_ContextHandle OH_AI_ContextCreate() { return MSContextCreate(); }

void OH_AI_ContextDestroy(OH_AI_ContextHandle* context) { MSContextDestroy(context); }

void OH_AI_ContextSetThreadNum(OH_AI_ContextHandle context, int32_t thread_num) {
    MSContextSetThreadNum(context, thread_num);
}

OH_AI_ModelHandle OH_AI_ModelCreate() { return MSModelCreate(); }

void OH_AI_ModelDestroy(OH_AI_ModelHandle* model) { MSModelDestroy(model); }

OH_AI_Status OH_AI_ModelBuildFromFile(OH_AI_ModelHandle model, const char* model_path,
                                      OH_AI_ModelType model_type,
                                      const OH_AI_ContextHandle model_context) {
    return static_cast<OH_AI_Status>(
        MSModelBuildFromFile(model, model_path, static_cast<MSModelType>(model_type), model_context));
}

OH_AI_Status OH_AI_ModelResize(OH_AI_ModelHandle model, const OH_AI_TensorHandleArray inputs,
                               OH_AI_ShapeInfo* shape_infos, size_t shape_info_num) {
    std::vector<MSShapeInfo> converted(shape_info_num);
    for (size_t i = 0; i < shape_info_num; ++i) {
        converted[i].shape_num = shape_infos[i].shape_num;
        std::memcpy(converted[i].shape, shape_infos[i].shape, sizeof(converted[i].shape));
    }
    return static_cast<OH_AI_Status>(
        MSModelResize(model, toMs(inputs), converted.data(), shape_info_num));
}

OH_AI_Status OH_AI_ModelPredict(OH_AI_ModelHandle model, const OH_AI_TensorHandleArray inputs,
                                OH_AI_TensorHandleArray* outputs, const OH_AI_KernelCallBack,
                                const OH_AI_KernelCallBack) {
    MSTensorHandleArray result{};
    const MSStatus status = MSModelPredict(model, toMs(inputs), &result, nullptr, nullptr);
    *outputs = toOh(result);
    return static_cast<OH_AI_Status>(status);
}

OH_AI_TensorHandleArray OH_AI_ModelGetInputs(const OH_AI_ModelHandle model) {
    return toOh(MSModelGetInputs(model));
}

const int64_t* OH_AI_TensorGetShape(const OH_AI_TensorHandle tensor, size_t* shape_num) {
    return MSTensorGetShape(tensor, shape_num);
}

size_t OH_AI_TensorGetDataSize(const OH_AI_TensorHandle tensor) {
    return MSTensorGetDataSize(tensor);
}

void* OH_AI_TensorGetMutableData(const OH_AI_TensorHandle tensor) {
    return MSTensorGetMutableData(tensor);
}

const void* OH_AI_TensorGetData(const OH_AI_TensorHandle tensor) {
    return MSTensorGetData(tensor);
}

}  // extern "C"
