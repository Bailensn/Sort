// Android JNI 胶水层：只做「jstring/jbyteArray ↔ C ABI」的类型转换，不含业务逻辑
#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

#include "engine_ocr_capi.h"

namespace {

struct OcrItemResult {
    float points[8] = {};
    float confidence = 0.0f;
    std::string text;
};

void collectItem(const engine_ocr_item *item, void *userData) {
    auto *out = static_cast<std::vector<OcrItemResult> *>(userData);
    OcrItemResult value;
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
    uint8_t hasInk = 0;
};

void collectCell(int32_t, const char *text, float confidence, int32_t hasInk, void *userData) {
    auto *out = static_cast<std::vector<CellResult> *>(userData);
    out->push_back(CellResult{text != nullptr ? text : "", confidence,
                              static_cast<uint8_t>(hasInk != 0 ? 1 : 0)});
}

OcrHandle *asOcrHandle(jlong value) {
    return reinterpret_cast<OcrHandle *>(static_cast<intptr_t>(value));
}

jobjectArray makeTriple(JNIEnv *env, jobject first, jobject second, jobject third) {
    jclass objectClass = env->FindClass("java/lang/Object");
    if (objectClass == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(3, objectClass, nullptr);
    env->DeleteLocalRef(objectClass);
    if (out == nullptr) return nullptr;
    env->SetObjectArrayElement(out, 0, first);
    env->SetObjectArrayElement(out, 1, second);
    env->SetObjectArrayElement(out, 2, third);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_bailensn_sort_OcrBridge_nativeOcrLastError(JNIEnv *, jclass) {
    return static_cast<jint>(engine_ocr_last_error());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bailensn_sort_OcrBridge_nativeOcrCreate(JNIEnv *env, jclass, jstring detPath,
                                                 jstring recPath, jstring charsetPath) {
    if (detPath == nullptr || recPath == nullptr || charsetPath == nullptr) return 0;

    const char *det = env->GetStringUTFChars(detPath, nullptr);
    const char *rec = env->GetStringUTFChars(recPath, nullptr);
    const char *charset = env->GetStringUTFChars(charsetPath, nullptr);
    if (det == nullptr || rec == nullptr || charset == nullptr) {
        if (det != nullptr) env->ReleaseStringUTFChars(detPath, det);
        if (rec != nullptr) env->ReleaseStringUTFChars(recPath, rec);
        if (charset != nullptr) env->ReleaseStringUTFChars(charsetPath, charset);
        return 0;
    }

    OcrHandle *handle = engine_ocr_create(det, rec, charset, 4);
    env->ReleaseStringUTFChars(detPath, det);
    env->ReleaseStringUTFChars(recPath, rec);
    env->ReleaseStringUTFChars(charsetPath, charset);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
}

JNIEXPORT void JNICALL
Java_com_bailensn_sort_OcrBridge_nativeOcrDestroy(JNIEnv *, jclass, jlong handle) {
    if (handle != 0) engine_ocr_destroy(asOcrHandle(handle));
}

JNIEXPORT jobjectArray JNICALL
Java_com_bailensn_sort_OcrBridge_nativeOcrRecognize(JNIEnv *env, jclass, jlong handle,
                                                    jbyteArray pixels, jint width, jint height,
                                                    jint isBgr) {
    if (pixels == nullptr) return nullptr;
    jbyte *buffer = env->GetByteArrayElements(pixels, nullptr);
    if (buffer == nullptr) return nullptr;

    std::vector<OcrItemResult> items;
    int32_t count = 0;
    const int32_t rc = engine_ocr_recognize(
        asOcrHandle(handle), reinterpret_cast<const uint8_t *>(buffer), width, height, 0, isBgr,
        collectItem, &items, &count);
    env->ReleaseByteArrayElements(pixels, buffer, JNI_ABORT);
    if (rc != ENGINE_OK) return nullptr;

    const jsize n = static_cast<jsize>(items.size());
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray texts = env->NewObjectArray(n, stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    if (texts == nullptr) return nullptr;

    std::vector<float> boxes(static_cast<size_t>(n) * 8u, 0.0f);
    std::vector<float> confidences(static_cast<size_t>(n), 0.0f);
    for (jsize i = 0; i < n; ++i) {
        for (int k = 0; k < 8; ++k) boxes[i * 8 + k] = items[i].points[k];
        confidences[i] = items[i].confidence;
        jstring text = env->NewStringUTF(items[i].text.c_str());
        env->SetObjectArrayElement(texts, i, text);
        env->DeleteLocalRef(text);
    }

    jfloatArray boxArray = env->NewFloatArray(n * 8);
    if (boxArray != nullptr) {
        env->SetFloatArrayRegion(boxArray, 0, n * 8, boxes.data());
    }
    jfloatArray confidenceArray = env->NewFloatArray(n);
    if (confidenceArray != nullptr) {
        env->SetFloatArrayRegion(confidenceArray, 0, n, confidences.data());
    }
    return makeTriple(env, texts, boxArray, confidenceArray);
}

static jobject recognizeCellsCommon(JNIEnv *env, jlong handle, jbyteArray pixels, jint width,
                                   jint height, jint isBgr, jintArray cells, jfloat scale,
                                   bool referee) {
    if (pixels == nullptr || cells == nullptr) return nullptr;
    const jsize cellValues = env->GetArrayLength(cells);
    if (cellValues <= 0 || cellValues % 4 != 0) return nullptr;

    std::vector<jint> raw(static_cast<size_t>(cellValues));
    env->GetIntArrayRegion(cells, 0, cellValues, raw.data());
    const jsize cellCount = cellValues / 4;
    std::vector<engine_ocr_cell> rects(static_cast<size_t>(cellCount));
    for (jsize i = 0; i < cellCount; ++i) {
        rects[i].left = raw[i * 4];
        rects[i].top = raw[i * 4 + 1];
        rects[i].right = raw[i * 4 + 2];
        rects[i].bottom = raw[i * 4 + 3];
    }

    jbyte *buffer = env->GetByteArrayElements(pixels, nullptr);
    if (buffer == nullptr) return nullptr;

    std::vector<CellResult> readings;
    auto recognize = referee ? engine_ocr_recognize_cells_ref : engine_ocr_recognize_cells;
    const int32_t rc = recognize(asOcrHandle(handle),
                                 reinterpret_cast<const uint8_t *>(buffer), width, height, 0, isBgr,
                                 rects.data(), cellCount, scale, collectCell, &readings);
    env->ReleaseByteArrayElements(pixels, buffer, JNI_ABORT);
    if (rc != ENGINE_OK) return nullptr;

    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray texts = env->NewObjectArray(cellCount, stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    if (texts == nullptr) return nullptr;

    std::vector<float> confidences(static_cast<size_t>(cellCount), 0.0f);
    std::vector<uint8_t> inks(static_cast<size_t>(cellCount), 0);
    for (jsize i = 0; i < cellCount; ++i) {
        if (i < static_cast<jsize>(readings.size())) {
            confidences[i] = readings[i].confidence;
            inks[i] = readings[i].hasInk;
            jstring text = env->NewStringUTF(readings[i].text.c_str());
            env->SetObjectArrayElement(texts, i, text);
            env->DeleteLocalRef(text);
        }
    }

    jfloatArray confidenceArray = env->NewFloatArray(cellCount);
    if (confidenceArray != nullptr) {
        env->SetFloatArrayRegion(confidenceArray, 0, cellCount, confidences.data());
    }
    jbyteArray inkArray = env->NewByteArray(cellCount);
    if (inkArray != nullptr) {
        env->SetByteArrayRegion(inkArray, 0, cellCount,
                                reinterpret_cast<const jbyte *>(inks.data()));
    }
    return makeTriple(env, texts, confidenceArray, inkArray);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_bailensn_sort_OcrBridge_nativeOcrRecognizeCells(JNIEnv *env, jclass, jlong handle,
                                                         jbyteArray pixels, jint width, jint height,
                                                         jint isBgr, jintArray cells,
                                                         jfloat scale) {
    return recognizeCellsCommon(env, handle, pixels, width, height, isBgr, cells, scale, false);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_bailensn_sort_OcrBridge_nativeOcrRecognizeCellsRef(JNIEnv *env, jclass, jlong handle,
                                                           jbyteArray pixels, jint width,
                                                           jint height, jint isBgr, jintArray cells,
                                                           jfloat scale) {
    return recognizeCellsCommon(env, handle, pixels, width, height, isBgr, cells, scale, true);
}

namespace {

struct TableCollector {
    std::vector<std::string> names;
    std::vector<int32_t> roles;
    std::vector<std::vector<std::string>> rows;
};

void collectHeader(int32_t, const char *name, int32_t role, void *userData) {
    auto *out = static_cast<TableCollector *>(userData);
    out->names.emplace_back(name != nullptr ? name : "");
    out->roles.push_back(role);
}

void collectRow(const engine_table_row *row, void *userData) {
    auto *out = static_cast<TableCollector *>(userData);
    if (row == nullptr) return;
    std::vector<std::string> cells;
    for (int32_t i = 0; i < row->columnCount; ++i) {
        cells.emplace_back(row->cells != nullptr ? row->cells[i] : "");
    }
    out->rows.push_back(std::move(cells));
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_bailensn_sort_OcrBridge_nativeTableAnalyze(JNIEnv *env, jclass, jlong handle,
                                                    jbyteArray pixels, jint width, jint height,
                                                    jint isBgr) {
    if (pixels == nullptr) return nullptr;
    jbyte *buffer = env->GetByteArrayElements(pixels, nullptr);
    if (buffer == nullptr) return nullptr;

    TableCollector collected;
    int32_t corrected = 0;
    int32_t suspicious = 0;
    const int32_t rc = engine_table_analyze(
        asOcrHandle(handle), reinterpret_cast<const uint8_t *>(buffer), width, height, 0, isBgr,
        collectRow, collectHeader, &corrected, &suspicious);
    env->ReleaseByteArrayElements(pixels, buffer, JNI_ABORT);
    if (rc != ENGINE_OK) return nullptr;

    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;

    jobjectArray rows = env->NewObjectArray(static_cast<jsize>(collected.rows.size()), env->FindClass("[Ljava/lang/String;"), nullptr);
    for (size_t r = 0; r < collected.rows.size(); ++r) {
        jobjectArray line = env->NewObjectArray(static_cast<jsize>(collected.rows[r].size()), stringClass, nullptr);
        for (size_t c = 0; c < collected.rows[r].size(); ++c) {
            jstring text = env->NewStringUTF(collected.rows[r][c].c_str());
            env->SetObjectArrayElement(line, static_cast<jsize>(c), text);
            env->DeleteLocalRef(text);
        }
        env->SetObjectArrayElement(rows, static_cast<jsize>(r), line);
        env->DeleteLocalRef(line);
    }

    jobjectArray names = env->NewObjectArray(static_cast<jsize>(collected.names.size()), stringClass, nullptr);
    for (size_t i = 0; i < collected.names.size(); ++i) {
        jstring text = env->NewStringUTF(collected.names[i].c_str());
        env->SetObjectArrayElement(names, static_cast<jsize>(i), text);
        env->DeleteLocalRef(text);
    }

    jintArray roles = env->NewIntArray(static_cast<jsize>(collected.roles.size()));
    if (roles != nullptr && !collected.roles.empty()) {
        env->SetIntArrayRegion(roles, 0, static_cast<jsize>(collected.roles.size()), collected.roles.data());
    }
    env->DeleteLocalRef(stringClass);

    jobjectArray triple = env->NewObjectArray(3, env->FindClass("java/lang/Object"), nullptr);
    env->SetObjectArrayElement(triple, 0, rows);
    env->SetObjectArrayElement(triple, 1, names);
    env->SetObjectArrayElement(triple, 2, roles);
    return triple;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bailensn_sort_OcrBridge_nativeOcrSetReferee(JNIEnv *env, jclass, jlong handle,
                                                     jstring recPath, jstring charsetPath) {
    if (recPath == nullptr || charsetPath == nullptr) return ENGINE_ERR_NULL;
    const char *rec = env->GetStringUTFChars(recPath, nullptr);
    const char *charset = env->GetStringUTFChars(charsetPath, nullptr);
    if (rec == nullptr || charset == nullptr) {
        if (rec != nullptr) env->ReleaseStringUTFChars(recPath, rec);
        if (charset != nullptr) env->ReleaseStringUTFChars(charsetPath, charset);
        return ENGINE_ERR_NULL;
    }
    const int32_t rc = engine_ocr_set_referee(asOcrHandle(handle), rec, charset);
    env->ReleaseStringUTFChars(recPath, rec);
    env->ReleaseStringUTFChars(charsetPath, charset);
    return rc;
}

}  // extern "C"
