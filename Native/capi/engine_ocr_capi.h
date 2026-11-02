/*
 * engine_ocr_capi.h —— OCR 的 C ABI 入口
 *
 * 只含 C 声明、extern "C"、不透明句柄；结果通过回调交出去，
 * 不跨边界分配内存、不让异常穿过。
 */

#ifndef ENGINE_OCR_CAPI_H
#define ENGINE_OCR_CAPI_H

#include <stdint.h>
#include <stddef.h>

#include "engine_errors.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ---- OCR 专用错误码 ---- */
#define ENGINE_ERR_NO_MODEL   -5  /* 模型文件打不开 */
#define ENGINE_ERR_NO_BACKEND -6  /* 没编进 ORT 或后端初始化失败 */

typedef struct OcrHandle OcrHandle;

/* 一个识别框：四个角按左上、右上、右下、左下排列，单位是输入图像素。 */
typedef struct engine_ocr_item {
    float x0, y0;
    float x1, y1;
    float x2, y2;
    float x3, y3;
    float confidence;
    const char *text; /* 回调期间有效，回调返回后失效 */
} engine_ocr_item;

typedef void (*engine_ocr_item_cb)(const engine_ocr_item *item, void *userData);

typedef struct engine_ocr_cell {
    int32_t left, top, right, bottom;
} engine_ocr_cell;

typedef void (*engine_ocr_cell_cb)(int32_t index, const char *text, float confidence,
                                   int32_t hasInk, void *userData);

/* ---- 整表分析：找线 → 切格 → 识别 → 清洗 → 表头 → 自检 ---- */

typedef struct engine_table_row {
    int32_t index;
    int32_t columnCount;
    const char *const *cells; /* 回调期间有效 */
} engine_table_row;

typedef void (*engine_table_row_cb)(const engine_table_row *row, void *userData);

typedef enum engine_column_role {
    ENGINE_COLUMN_OTHER = 0,
    ENGINE_COLUMN_SEQUENCE = 1,
    ENGINE_COLUMN_ID = 2,
    ENGINE_COLUMN_SUBJECT = 3,
    ENGINE_COLUMN_TOTAL = 4
} engine_column_role;

typedef void (*engine_table_header_cb)(int32_t index, const char *name, int32_t role,
                                       void *userData);

/* 结果逐行回调；表头逐列回调；纠正格数与存疑行数写回 out。 */
int32_t engine_table_analyze(OcrHandle *h, const uint8_t *pixels, int32_t width, int32_t height,
                             int32_t stride, int32_t isBgr, engine_table_row_cb onRow,
                             engine_table_header_cb onHeader, int32_t *outCorrected,
                             int32_t *outSuspicious);

/* ---- 生命周期 ---- */
OcrHandle *engine_ocr_create(const char *detModelPath, const char *recModelPath,
                             const char *charsetPath, int32_t numThreads);

/* create 返回 NULL 时用它取原因：-1 参数空、-3 异常、-5 模型/字符表打不开、
 * -6 没编进推理后端或后端初始化失败。成功时归零。 */
int32_t engine_ocr_last_error(void);
void engine_ocr_destroy(OcrHandle *h);

/* ---- 整图识别：pixels 是 RGB 或 BGR 的 8 位三通道缓冲 ----
 * stride 是每行字节数，传 0 表示 width * 3；isBgr 非 0 表示缓冲是 BGR。
 * 结果逐条回调，outCount 回填总条数。 */
int32_t engine_ocr_recognize(OcrHandle *h, const uint8_t *pixels, int32_t width, int32_t height,
                             int32_t stride, int32_t isBgr, engine_ocr_item_cb cb,
                             void *userData, int32_t *outCount);

/* ---- 复核模型：第二套 rec 后端，只给有争议的格子投票 ----
 * 成功返回 ENGINE_OK；没有模型文件或后端初始化失败返回 ENGINE_ERR_NO_BACKEND。 */
int32_t engine_ocr_set_referee(OcrHandle *h, const char *recModelPath, const char *charsetPath);

/* ---- 逐格识别：cells 里是原图像素坐标，scale > 1 时放大重认 ----
 * engine_ocr_recognize_cells_ref 用复核模型跑同一批格子；
 * 没配置复核模型时返回 ENGINE_ERR_NO_BACKEND。 */
int32_t engine_ocr_recognize_cells(OcrHandle *h, const uint8_t *pixels, int32_t width,
                                   int32_t height, int32_t stride, int32_t isBgr,
                                   const engine_ocr_cell *cells, int32_t cellCount, float scale,
                                   engine_ocr_cell_cb cb, void *userData);

int32_t engine_ocr_recognize_cells_ref(OcrHandle *h, const uint8_t *pixels, int32_t width,
                                       int32_t height, int32_t stride, int32_t isBgr,
                                       const engine_ocr_cell *cells, int32_t cellCount, float scale,
                                       engine_ocr_cell_cb cb, void *userData);

#ifdef __cplusplus
}
#endif

#endif /* ENGINE_OCR_CAPI_H */
