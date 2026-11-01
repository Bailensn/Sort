// 核心链路的冒烟测试：用假后端跑 det -> 排序 -> 裁图 -> rec，不依赖模型和 ORT。
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "ocr/OcrEngine.hpp"

namespace {

class FakeBackend : public ocr::InferenceBackend {
public:
    bool runDet(const ocr::Tensor& input, ocr::Tensor& output) override {
        const int height = static_cast<int>(input.dims[2]);
        const int width = static_cast<int>(input.dims[3]);
        output.dims = {1, 1, height, width};
        output.data.assign(static_cast<size_t>(height) * width, 0.0f);
        for (int y = height / 3; y < height * 2 / 3; ++y) {
            for (int x = width / 6; x < width * 5 / 6; ++x) {
                output.data[static_cast<size_t>(y) * width + x] = 0.95f;
            }
        }
        return true;
    }

    bool runRec(const ocr::Tensor& input, ocr::Tensor& output) override {
        const int batch = static_cast<int>(input.dims[0]);
        output.dims = {batch, 4, 5};
        output.data.assign(static_cast<size_t>(batch) * 4 * 5, 0.0f);
        for (int b = 0; b < batch; ++b) {
            const size_t base = static_cast<size_t>(b) * 4 * 5;
            output.data[base + 1] = 0.9f;   // A
            output.data[base + 5 + 2] = 0.8f;  // B
            output.data[base + 10 + 0] = 0.99f;  // blank
            output.data[base + 15 + 1] = 0.9f;   // A
        }
        return true;
    }
};

int failures = 0;

void expect(bool condition, const char* name) {
    std::printf("%s %s\n", condition ? "[ok]  " : "[FAIL]", name);
    if (!condition) ++failures;
}

}  // namespace

int main() {
    ocr::Image8 image;
    image.width = 200;
    image.height = 100;
    image.order = ocr::ColorOrder::BGR;
    image.data.assign(static_cast<size_t>(200) * 100 * 3, 255);

    ocr::OcrEngine engine;
    engine.setCharset({"A", "B", "C", " "});
    engine.setBackend(std::make_unique<FakeBackend>());

    const ocr::OcrResult result = engine.recognize(image);
    expect(result.items.size() == 1, "整图识别出 1 个框");
    expect(!result.items.empty() && result.items[0].text == "ABA", "CTC 解码得到 ABA");
    expect(!result.items.empty() && result.items[0].confidence > 0.8f, "置信度是保留帧的均值");

    // 左边涂黑造出墨迹，右边保持全白。
    for (int y = 20; y < 60; ++y) {
        for (int x = 20; x < 60; ++x) {
            uint8_t* pixel = image.row(y) + static_cast<size_t>(x) * 3;
            pixel[0] = 0;
            pixel[1] = 0;
            pixel[2] = 0;
        }
    }

    const std::vector<ocr::Rect> cells = {ocr::Rect{10, 10, 90, 90}, ocr::Rect{110, 10, 190, 90}};
    const std::vector<ocr::CellReading> readings = engine.recognizeCells(image, cells);
    expect(readings.size() == 2, "两个格子都返回结果");
    expect(readings[0].hasInk, "有墨的格子被送去识别");
    expect(readings[0].text == "ABA", "有墨格子的文本");
    expect(!readings[1].hasInk, "空白格不送模型");
    expect(readings[1].text.empty(), "空白格文本为空");

    std::printf(failures == 0 ? "全部通过\n" : "失败 %d 项\n", failures);
    return failures == 0 ? 0 : 1;
}
