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
    std::vector<int32_t> notes;  // 三元组：kind, a, b
    std::vector<int32_t> suspicious;
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

void collectIndex(int32_t index, void *userData) {
    static_cast<TableCollector *>(userData)->suspicious.push_back(index);
}

void collectNote(int32_t kind, int32_t a, int32_t b, void *userData) {
    auto *out = static_cast<TableCollector *>(userData);
    out->notes.push_back(kind);
    out->notes.push_back(a);
    out->notes.push_back(b);
}

jobjectArray makeStringRow(JNIEnv *env, jclass stringClass, const std::vector<std::string> &cells) {
    jobjectArray line = env->NewObjectArray(static_cast<jsize>(cells.size()), stringClass, nullptr);
    if (line == nullptr) return nullptr;
    for (size_t c = 0; c < cells.size(); ++c) {
        jstring text = env->NewStringUTF(cells[c].c_str());
        env->SetObjectArrayElement(line, static_cast<jsize>(c), text);
        env->DeleteLocalRef(text);
    }
    return line;
}

jobjectArray makeStringRows(JNIEnv *env, const std::vector<std::vector<std::string>> &rows) {
    jclass stringClass = env->FindClass("java/lang/String");
    jclass rowClass = env->FindClass("[Ljava/lang/String;");
    if (stringClass == nullptr || rowClass == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(rows.size()), rowClass, nullptr);
    if (out != nullptr) {
        for (size_t r = 0; r < rows.size(); ++r) {
            jobjectArray line = makeStringRow(env, stringClass, rows[r]);
            env->SetObjectArrayElement(out, static_cast<jsize>(r), line);
            env->DeleteLocalRef(line);
        }
    }
    env->DeleteLocalRef(rowClass);
    env->DeleteLocalRef(stringClass);
    return out;
}

jintArray makeIntArray(JNIEnv *env, const std::vector<int32_t> &values) {
    jintArray out = env->NewIntArray(static_cast<jsize>(values.size()));
    if (out != nullptr && !values.empty()) {
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(values.size()), values.data());
    }
    return out;
}

/// 图像跨 JNI 一律转成 ARGB 整数：Kotlin 侧能直接 createBitmap，不用逐像素循环。
jobjectArray makeImage(JNIEnv *env, const engine_table_image &image) {
    jclass objectClass = env->FindClass("java/lang/Object");
    jintArray sizeArray = env->NewIntArray(2);
    if (objectClass == nullptr || sizeArray == nullptr) return nullptr;

    const jsize count = static_cast<jsize>(image.width) * static_cast<jsize>(image.height);
    jintArray argb = env->NewIntArray(count);
    if (argb != nullptr && count > 0 && image.data != nullptr) {
        std::vector<jint> pixels(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            const uint8_t *pixel = image.data + static_cast<size_t>(i) * 3u;
            pixels[static_cast<size_t>(i)] = static_cast<jint>(
                0xFF000000u | (static_cast<uint32_t>(pixel[0]) << 16) |
                (static_cast<uint32_t>(pixel[1]) << 8) | static_cast<uint32_t>(pixel[2]));
        }
        env->SetIntArrayRegion(argb, 0, count, pixels.data());
    }
    const jint size[2] = {image.width, image.height};
    env->SetIntArrayRegion(sizeArray, 0, 2, size);

    jobjectArray out = env->NewObjectArray(2, objectClass, nullptr);
    if (out != nullptr) {
        env->SetObjectArrayElement(out, 0, argb);
        env->SetObjectArrayElement(out, 1, sizeArray);
    }
    env->DeleteLocalRef(sizeArray);
    env->DeleteLocalRef(argb);
    env->DeleteLocalRef(objectClass);
    return out;
}

/// 二维字符串数组 → 行优先展平矩阵；cells 的底层缓冲要被调用方持有到调用结束。
struct MatrixBuffer {
    std::vector<int32_t> columnCounts;
    std::vector<std::string> cells;

    std::vector<const char *> pointers() const {
        std::vector<const char *> out;
        out.reserve(cells.size());
        for (const auto &cell : cells) out.push_back(cell.c_str());
        return out;
    }
};

bool readMatrix(JNIEnv *env, jobjectArray rows, MatrixBuffer &out) {
    if (rows == nullptr) return false;
    const jsize rowCount = env->GetArrayLength(rows);
    out.columnCounts.reserve(static_cast<size_t>(rowCount));
    for (jsize r = 0; r < rowCount; ++r) {
        auto line = static_cast<jobjectArray>(env->GetObjectArrayElement(rows, r));
        if (line == nullptr) {
            out.columnCounts.push_back(0);
            continue;
        }
        const jsize columns = env->GetArrayLength(line);
        out.columnCounts.push_back(columns);
        for (jsize c = 0; c < columns; ++c) {
            auto text = static_cast<jstring>(env->GetObjectArrayElement(line, c));
            if (text == nullptr) {
                out.cells.emplace_back();
                continue;
            }
            const char *utf = env->GetStringUTFChars(text, nullptr);
            out.cells.emplace_back(utf != nullptr ? utf : "");
            if (utf != nullptr) env->ReleaseStringUTFChars(text, utf);
            env->DeleteLocalRef(text);
        }
        env->DeleteLocalRef(line);
    }
    return true;
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
    engine_table_stats stats{};
    engine_table_image overlay{};
    engine_table_image enhanced{};
    // 工作图不往回传：界面用的是叠线图，多拷一份只会白占内存。
    const int32_t rc = engine_table_analyze(
        asOcrHandle(handle), reinterpret_cast<const uint8_t *>(buffer), width, height, 0, isBgr,
        collectRow, collectHeader, collectNote, collectIndex, &stats, nullptr, &overlay, &enhanced);
    env->ReleaseByteArrayElements(pixels, buffer, JNI_ABORT);
    if (rc != ENGINE_OK) {
        engine_table_image_free(&overlay);
        engine_table_image_free(&enhanced);
        return nullptr;
    }

    jclass objectClass = env->FindClass("java/lang/Object");
    jclass stringClass = env->FindClass("java/lang/String");
    if (objectClass == nullptr || stringClass == nullptr) {
        engine_table_image_free(&overlay);
        engine_table_image_free(&enhanced);
        return nullptr;
    }

    jobjectArray rows = makeStringRows(env, collected.rows);
    jobjectArray names = env->NewObjectArray(static_cast<jsize>(collected.names.size()), stringClass,
                                             nullptr);
    if (names != nullptr) {
        for (size_t i = 0; i < collected.names.size(); ++i) {
            jstring text = env->NewStringUTF(collected.names[i].c_str());
            env->SetObjectArrayElement(names, static_cast<jsize>(i), text);
            env->DeleteLocalRef(text);
        }
    }
    jintArray roles = makeIntArray(env, collected.roles);
    jintArray notes = makeIntArray(env, collected.notes);
    jintArray suspicious = makeIntArray(env, collected.suspicious);

    const std::vector<int32_t> intStats = {
        stats.byLines,          stats.rectified,       stats.horizontalLines, stats.verticalLines,
        stats.cellCount,        stats.correctedCells,  stats.suspiciousRows,  stats.lowConfidenceCells,
        stats.headerFromImage};
    const std::vector<jfloat> floatStats = {stats.averageConfidence, stats.confidenceMin,
                                            stats.confidenceMax, stats.deskewDegrees};
    const std::vector<jdouble> times = {stats.elapsedMs, stats.cellMs, stats.detectMs};

    jintArray intStatsArray = makeIntArray(env, intStats);
    jfloatArray floatStatsArray = env->NewFloatArray(static_cast<jsize>(floatStats.size()));
    if (floatStatsArray != nullptr) {
        env->SetFloatArrayRegion(floatStatsArray, 0, static_cast<jsize>(floatStats.size()),
                                 floatStats.data());
    }
    jdoubleArray timesArray = env->NewDoubleArray(static_cast<jsize>(times.size()));
    if (timesArray != nullptr) {
        env->SetDoubleArrayRegion(timesArray, 0, static_cast<jsize>(times.size()), times.data());
    }

    jobjectArray overlayPair = makeImage(env, overlay);
    jobjectArray enhancedPair = makeImage(env, enhanced);
    engine_table_image_free(&overlay);
    engine_table_image_free(&enhanced);

    constexpr jsize kParts = 11;
    jobjectArray parts = env->NewObjectArray(kParts, objectClass, nullptr);
    if (parts != nullptr) {
        env->SetObjectArrayElement(parts, 0, rows);
        env->SetObjectArrayElement(parts, 1, names);
        env->SetObjectArrayElement(parts, 2, roles);
        env->SetObjectArrayElement(parts, 3, notes);
        env->SetObjectArrayElement(parts, 4, suspicious);
        env->SetObjectArrayElement(parts, 5, intStatsArray);
        env->SetObjectArrayElement(parts, 6, floatStatsArray);
        env->SetObjectArrayElement(parts, 7, timesArray);
        env->SetObjectArrayElement(parts, 9, overlayPair);
        env->SetObjectArrayElement(parts, 10, enhancedPair);
    }
    env->DeleteLocalRef(stringClass);
    env->DeleteLocalRef(objectClass);
    return parts;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_bailensn_sort_NativeTable_nativeDetectHeader(JNIEnv *env, jclass, jobjectArray rows) {
    MatrixBuffer buffer;
    if (!readMatrix(env, rows, buffer)) return nullptr;

    TableCollector collected;
    int32_t fromImage = 0;
    const std::vector<const char *> pointers = buffer.pointers();
    const engine_table_matrix matrix{static_cast<int32_t>(buffer.columnCounts.size()),
                                     buffer.columnCounts.data(), pointers.data()};
    const int32_t rc = engine_table_detect_header(&matrix, collectHeader, &fromImage);
    if (rc != ENGINE_OK) return nullptr;

    jclass objectClass = env->FindClass("java/lang/Object");
    jclass stringClass = env->FindClass("java/lang/String");
    if (objectClass == nullptr || stringClass == nullptr) return nullptr;

    jobjectArray names = env->NewObjectArray(static_cast<jsize>(collected.names.size()), stringClass,
                                             nullptr);
    if (names != nullptr) {
        for (size_t i = 0; i < collected.names.size(); ++i) {
            jstring text = env->NewStringUTF(collected.names[i].c_str());
            env->SetObjectArrayElement(names, static_cast<jsize>(i), text);
            env->DeleteLocalRef(text);
        }
    }
    jintArray roles = makeIntArray(env, collected.roles);
    jintArray flags = env->NewIntArray(1);
    if (flags != nullptr) {
        const jint value = fromImage;
        env->SetIntArrayRegion(flags, 0, 1, &value);
    }

    jobjectArray parts = env->NewObjectArray(3, objectClass, nullptr);
    if (parts != nullptr) {
        env->SetObjectArrayElement(parts, 0, names);
        env->SetObjectArrayElement(parts, 1, roles);
        env->SetObjectArrayElement(parts, 2, flags);
    }
    env->DeleteLocalRef(stringClass);
    env->DeleteLocalRef(objectClass);
    return parts;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_bailensn_sort_NativeTable_nativeSelfCheck(JNIEnv *env, jclass, jobjectArray rows,
                                                      jint totalColumn, jintArray subjectColumns) {
    MatrixBuffer buffer;
    if (!readMatrix(env, rows, buffer)) return nullptr;

    std::vector<int32_t> subjects;
    if (subjectColumns != nullptr) {
        const jsize count = env->GetArrayLength(subjectColumns);
        subjects.resize(static_cast<size_t>(count));
        if (count > 0) env->GetIntArrayRegion(subjectColumns, 0, count, subjects.data());
    }

    TableCollector collected;
    int32_t corrected = 0;
    int32_t suspicious = 0;
    const std::vector<const char *> pointers = buffer.pointers();
    const engine_table_matrix matrix{static_cast<int32_t>(buffer.columnCounts.size()),
                                     buffer.columnCounts.data(), pointers.data()};
    const int32_t rc = engine_table_self_check(
        &matrix, nullptr, totalColumn, subjects.empty() ? nullptr : subjects.data(),
        static_cast<int32_t>(subjects.size()), collectRow, collectNote, collectIndex, &corrected,
        &suspicious);
    if (rc != ENGINE_OK) return nullptr;

    jclass objectClass = env->FindClass("java/lang/Object");
    if (objectClass == nullptr) return nullptr;

    jobjectArray outRows = makeStringRows(env, collected.rows);
    jintArray notes = makeIntArray(env, collected.notes);
    jintArray suspiciousRows = makeIntArray(env, collected.suspicious);
    const std::vector<int32_t> counts = {corrected, suspicious};
    jintArray countsArray = makeIntArray(env, counts);

    jobjectArray parts = env->NewObjectArray(4, objectClass, nullptr);
    if (parts != nullptr) {
        env->SetObjectArrayElement(parts, 0, outRows);
        env->SetObjectArrayElement(parts, 1, notes);
        env->SetObjectArrayElement(parts, 2, countsArray);
        env->SetObjectArrayElement(parts, 3, suspiciousRows);
    }
    env->DeleteLocalRef(objectClass);
    return parts;
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
