// HarmonyOS NAPI 胶水层：只做「napi_value ↔ C ABI」转换，不含业务逻辑
#include "napi/native_api.h"
#include "hilog/log.h"

#include <cstdint>
#include <string>
#include <vector>

#include "engine_ocr_capi.h"

namespace {

struct ItemResult {
    float points[8] = {};
    float confidence = 0.0f;
    std::string text;
};

void collectItem(const engine_ocr_item *item, void *userData) {
    auto *out = static_cast<std::vector<ItemResult> *>(userData);
    ItemResult value;
    value.points[0] = item->x0;
    value.points[1] = item->y0;
    value.points[2] = item->x1;
    value.points[3] = item->y1;
    value.points[4] = item->x2;
    value.points[5] = item->y2;
    value.points[6] = item->x3;
    value.points[7] = item->y3;
    value.confidence = item->confidence;
    value.text = item->text != nullptr ? item->text : "";
    out->push_back(std::move(value));
}

struct CellResult {
    std::string text;
    float confidence = 0.0f;
    bool hasInk = false;
};

void collectCell(int32_t, const char *text, float confidence, int32_t hasInk, void *userData) {
    auto *out = static_cast<std::vector<CellResult> *>(userData);
    out->push_back(CellResult{text != nullptr ? text : "", confidence, hasInk != 0});
}

OcrHandle *asHandle(int64_t value) {
    return reinterpret_cast<OcrHandle *>(static_cast<intptr_t>(value));
}

bool readString(napi_env env, napi_value value, std::string &out) {
    size_t length = 0;
    if (napi_get_value_string_utf8(env, value, nullptr, 0, &length) != napi_ok) return false;
    out.assign(length + 1, '\0');
    return napi_get_value_string_utf8(env, value, out.data(), length + 1, &length) == napi_ok;
}

bool readInt64(napi_env env, napi_value value, int64_t &out) {
    return napi_get_value_int64(env, value, &out) == napi_ok;
}

bool readInt32(napi_env env, napi_value value, int32_t &out) {
    return napi_get_value_int32(env, value, &out) == napi_ok;
}

bool readFloat(napi_env env, napi_value value, double &out) {
    return napi_get_value_double(env, value, &out) == napi_ok;
}

void setNumber(napi_env env, napi_value object, const char *name, double value) {
    napi_value number;
    if (napi_create_double(env, value, &number) == napi_ok) {
        napi_set_named_property(env, object, name, number);
    }
}

napi_value makeLine(napi_env env, const ItemResult &item) {
    napi_value object;
    napi_create_object(env, &object);
    setNumber(env, object, "x0", item.points[0]);
    setNumber(env, object, "y0", item.points[1]);
    setNumber(env, object, "x1", item.points[2]);
    setNumber(env, object, "y1", item.points[3]);
    setNumber(env, object, "x2", item.points[4]);
    setNumber(env, object, "y2", item.points[5]);
    setNumber(env, object, "x3", item.points[6]);
    setNumber(env, object, "y3", item.points[7]);
    setNumber(env, object, "confidence", item.confidence);

    napi_value text;
    napi_create_string_utf8(env, item.text.c_str(), NAPI_AUTO_LENGTH, &text);
    napi_set_named_property(env, object, "text", text);
    return object;
}

napi_value makeCell(napi_env env, const CellResult &reading) {
    napi_value object;
    napi_create_object(env, &object);

    napi_value text;
    napi_create_string_utf8(env, reading.text.c_str(), NAPI_AUTO_LENGTH, &text);
    napi_set_named_property(env, object, "text", text);
    setNumber(env, object, "confidence", reading.confidence);

    napi_value hasInk;
    napi_get_boolean(env, reading.hasInk, &hasInk);
    napi_set_named_property(env, object, "hasInk", hasInk);
    return object;
}

}  // namespace

static napi_value Create(napi_env env, napi_callback_info info) {
    size_t argc = 3;
    napi_value args[3] = {nullptr, nullptr, nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 3) return nullptr;

    std::string detPath;
    std::string recPath;
    std::string charsetPath;
    if (!readString(env, args[0], detPath) || !readString(env, args[1], recPath) ||
        !readString(env, args[2], charsetPath)) {
        return nullptr;
    }

    OcrHandle *handle = engine_ocr_create(detPath.c_str(), recPath.c_str(), charsetPath.c_str(), 4);
    napi_value out;
    napi_create_int64(env, static_cast<int64_t>(reinterpret_cast<intptr_t>(handle)), &out);
    return out;
}

static napi_value Destroy(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    int64_t handle = 0;
    if (argc >= 1 && readInt64(env, args[0], handle) && handle != 0) {
        engine_ocr_destroy(asHandle(handle));
    }
    return nullptr;
}

static napi_value Recognize(napi_env env, napi_callback_info info) {
    size_t argc = 5;
    napi_value args[5] = {nullptr, nullptr, nullptr, nullptr, nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 5) return nullptr;

    int64_t handle = 0;
    int32_t width = 0;
    int32_t height = 0;
    int32_t isBgr = 0;
    if (!readInt64(env, args[0], handle) || !readInt32(env, args[2], width) ||
        !readInt32(env, args[3], height) || !readInt32(env, args[4], isBgr)) {
        return nullptr;
    }

    // Uint8Array 直接用底层缓冲，不拷贝。
    napi_typedarray_type type;
    size_t length = 0;
    void *data = nullptr;
    if (napi_get_typedarray_info(env, args[1], &type, &length, &data, nullptr, nullptr) != napi_ok) {
        return nullptr;
    }
    if (type != napi_uint8_array || data == nullptr) return nullptr;

    std::vector<ItemResult> items;
    int32_t count = 0;
    const int32_t rc = engine_ocr_recognize(asHandle(handle), static_cast<const uint8_t *>(data),
                                            width, height, 0, isBgr, collectItem, &items, &count);
    if (rc != ENGINE_OK) return nullptr;

    napi_value array;
    napi_create_array_with_length(env, items.size(), &array);
    for (size_t i = 0; i < items.size(); ++i) {
        napi_set_element(env, array, i, makeLine(env, items[i]));
    }
    return array;
}

static napi_value RecognizeCells(napi_env env, napi_callback_info info) {
    size_t argc = 7;
    napi_value args[7] = {nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 7) return nullptr;

    int64_t handle = 0;
    int32_t width = 0;
    int32_t height = 0;
    int32_t isBgr = 0;
    double scale = 1.0;
    if (!readInt64(env, args[0], handle) || !readInt32(env, args[2], width) ||
        !readInt32(env, args[3], height) || !readInt32(env, args[4], isBgr) ||
        !readFloat(env, args[6], scale)) {
        return nullptr;
    }

    napi_typedarray_type pixelType;
    size_t pixelLength = 0;
    void *pixelData = nullptr;
    if (napi_get_typedarray_info(env, args[1], &pixelType, &pixelLength, &pixelData, nullptr,
                                 nullptr) != napi_ok ||
        pixelType != napi_uint8_array || pixelData == nullptr) {
        return nullptr;
    }

    napi_typedarray_type cellType;
    size_t cellLength = 0;
    void *cellData = nullptr;
    if (napi_get_typedarray_info(env, args[5], &cellType, &cellLength, &cellData, nullptr,
                                 nullptr) != napi_ok ||
        cellType != napi_int32_array || cellData == nullptr) {
        return nullptr;
    }

    const int32_t *raw = static_cast<const int32_t *>(cellData);
    const int32_t cellCount = static_cast<int32_t>(cellLength);
    std::vector<engine_ocr_cell> cells(static_cast<size_t>(cellCount));
    for (int32_t i = 0; i < cellCount; ++i) {
        cells[i].left = raw[i * 4];
        cells[i].top = raw[i * 4 + 1];
        cells[i].right = raw[i * 4 + 2];
        cells[i].bottom = raw[i * 4 + 3];
    }

    std::vector<CellResult> readings;
    const int32_t rc = engine_ocr_recognize_cells(
        asHandle(handle), static_cast<const uint8_t *>(pixelData), width, height, 0, isBgr,
        cells.data(), cellCount, static_cast<float>(scale), collectCell, &readings);
    if (rc != ENGINE_OK) return nullptr;

    napi_value array;
    napi_create_array_with_length(env, readings.size(), &array);
    for (size_t i = 0; i < readings.size(); ++i) {
        napi_set_element(env, array, i, makeCell(env, readings[i]));
    }
    return array;
}

// 模块注册
EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor desc[] = {
        {"create", nullptr, Create, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"destroy", nullptr, Destroy, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"recognize", nullptr, Recognize, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"recognizeCells", nullptr, RecognizeCells, nullptr, nullptr, nullptr, napi_default,
         nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}
EXTERN_C_END

// nm_modname 必须和 .so 名一致：libmain.so → "main"
static napi_module engineModule = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "main",
    .nm_priv = nullptr,
    .reserved = {nullptr},
};

extern "C" __attribute__((constructor)) void RegisterEngineModule(void) {
    napi_module_register(&engineModule);
}
