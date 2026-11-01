// ocr_dump：跑完整链路并输出 JSON，用来给两端对拍。
// 输入只支持 PPM(P6)/PGM(P5)，避免为了读图拖进解码库。
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "ocr/OcrEngine.hpp"
#include "OnnxBackend.hpp"

namespace {

struct Image {
    std::vector<uint8_t> pixels;  // RGB 三通道
    int width = 0;
    int height = 0;
};

bool readToken(FILE* file, char* buffer, size_t size) {
    size_t length = 0;
    int ch = 0;
    // 跳过空白和注释行。
    while ((ch = std::fgetc(file)) != EOF) {
        if (ch == '#') {
            while (ch != EOF && ch != '\n') ch = std::fgetc(file);
            continue;
        }
        if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') break;
    }
    if (ch == EOF) return false;

    while (ch != EOF && ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') {
        if (length + 1 < size) buffer[length++] = static_cast<char>(ch);
        ch = std::fgetc(file);
    }
    buffer[length] = '\0';
    return length > 0;
}

bool loadPnm(const std::string& path, Image& out) {
    FILE* file = std::fopen(path.c_str(), "rb");
    if (file == nullptr) return false;

    char token[64] = {};
    if (!readToken(file, token, sizeof(token))) {
        std::fclose(file);
        return false;
    }
    const bool isPpm = std::strcmp(token, "P6") == 0;
    const bool isPgm = std::strcmp(token, "P5") == 0;
    if (!isPpm && !isPgm) {
        std::fclose(file);
        return false;
    }

    if (!readToken(file, token, sizeof(token))) {
        std::fclose(file);
        return false;
    }
    out.width = std::atoi(token);
    if (!readToken(file, token, sizeof(token))) {
        std::fclose(file);
        return false;
    }
    out.height = std::atoi(token);
    if (!readToken(file, token, sizeof(token))) {
        std::fclose(file);
        return false;
    }
    const int maxValue = std::atoi(token);
    if (out.width <= 0 || out.height <= 0 || maxValue != 255) {
        std::fclose(file);
        return false;
    }

    const int channels = isPpm ? 3 : 1;
    std::vector<uint8_t> raw(static_cast<size_t>(out.width) * out.height * channels);
    const size_t read = std::fread(raw.data(), 1, raw.size(), file);
    std::fclose(file);
    if (read != raw.size()) return false;

    if (isPpm) {
        out.pixels = std::move(raw);
    } else {
        out.pixels.resize(static_cast<size_t>(out.width) * out.height * 3);
        for (size_t i = 0; i < raw.size(); ++i) {
            out.pixels[i * 3] = raw[i];
            out.pixels[i * 3 + 1] = raw[i];
            out.pixels[i * 3 + 2] = raw[i];
        }
    }
    return true;
}

void printJson(const ocr::OcrResult& result) {
    std::printf("{\"detectionMs\":%.1f,\"recognitionMs\":%.1f,\"items\":[",
                result.detectionMs, result.recognitionMs);
    for (size_t i = 0; i < result.items.size(); ++i) {
        const ocr::OcrItem& item = result.items[i];
        if (i > 0) std::printf(",");
        std::printf("{\"text\":\"%s\",\"confidence\":%.4f,\"points\":[[%.1f,%.1f],[%.1f,%.1f],"
                    "[%.1f,%.1f],[%.1f,%.1f]]}",
                    item.text.c_str(), item.confidence, item.box.points[0].x, item.box.points[0].y,
                    item.box.points[1].x, item.box.points[1].y, item.box.points[2].x,
                    item.box.points[2].y, item.box.points[3].x, item.box.points[3].y);
    }
    std::printf("]}\n");
}

}  // namespace

int main(int argc, char** argv) {
    std::string detPath;
    std::string recPath;
    std::string charsetPath;
    std::string imagePath;

    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        auto take = [&](std::string& target) {
            if (i + 1 < argc) target = argv[++i];
        };
        if (arg == "--det") {
            take(detPath);
        } else if (arg == "--rec") {
            take(recPath);
        } else if (arg == "--charset") {
            take(charsetPath);
        } else if (arg == "--image") {
            take(imagePath);
        }
    }

    if (detPath.empty() || recPath.empty() || charsetPath.empty() || imagePath.empty()) {
        std::fprintf(stderr,
                     "用法: ocr_dump --det det.onnx --rec rec.onnx --charset rec.yml --image a.ppm\n");
        return 2;
    }

    Image image;
    if (!loadPnm(imagePath, image)) {
        std::fprintf(stderr, "读图失败（只支持 P6/P5、maxval 255）: %s\n", imagePath.c_str());
        return 2;
    }

    ocr::OcrEngine engine;
    if (!engine.loadCharsetFromFile(charsetPath)) {
        std::fprintf(stderr, "字符表解析失败: %s\n", charsetPath.c_str());
        return 2;
    }

    auto backend = ocr::OnnxBackend::create(detPath, recPath, 4);
    if (!backend) {
        std::fprintf(stderr, "模型加载失败（或编译时没打开 SORT_WITH_ONNXRUNTIME）\n");
        return 3;
    }
    engine.setBackend(std::move(backend));

    ocr::Image8 frame;
    frame.width = image.width;
    frame.height = image.height;
    frame.order = ocr::ColorOrder::RGB;
    frame.data = std::move(image.pixels);

    printJson(engine.recognize(frame));
    return 0;
}
