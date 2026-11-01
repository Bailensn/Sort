#include "Preprocess.hpp"

#include <algorithm>
#include <cmath>

namespace ocr {
namespace {

constexpr int kDetAlign = 32;
constexpr int kRecHeight = 48;
constexpr int kRecMaxWidth = 3200;

constexpr double kDetScale = 1.0 / 255.0;
constexpr double kDetMean[3] = {0.485, 0.456, 0.406};
constexpr double kDetStd[3] = {0.229, 0.224, 0.225};

double limitRatio(int width, int height, const OcrConfig& config) {
    const int longest = std::max(width, height);
    const int shortest = std::min(width, height);
    switch (config.detLimitType) {
        case LimitType::Max:
            return longest > config.detLimitSideLen
                       ? static_cast<double>(config.detLimitSideLen) / longest
                       : 1.0;
        case LimitType::Min:
            return shortest < config.detLimitSideLen
                       ? static_cast<double>(config.detLimitSideLen) / shortest
                       : 1.0;
        case LimitType::ResizeLong:
            return static_cast<double>(config.detLimitSideLen) / longest;
    }
    return 1.0;
}

void fillChannels(Tensor& tensor, const Image8& image, int width, int height,
                  const double mean[3], const double std[3], double scale) {
    const int channelSize = width * height;
    tensor.data.assign(static_cast<size_t>(channelSize) * 3, 0.0f);

    for (int y = 0; y < height; ++y) {
        const uint8_t* row = image.row(y);
        for (int x = 0; x < width; ++x) {
            const int index = y * width + x;
            for (int c = 0; c < 3; ++c) {
                const double value = row[x * 3 + c] * scale;
                tensor.data[static_cast<size_t>(c) * static_cast<size_t>(channelSize) +
                              static_cast<size_t>(index)] =
                    static_cast<float>((value - mean[c]) / std[c]);
            }
        }
    }
}

}  // namespace

DetPreprocessResult preprocessDet(const Image8& src, const OcrConfig& config) {
    DetPreprocessResult result;
    if (src.empty()) return result;

    // det 模型按 ImageNet 的 RGB 顺序训练，统一换成 RGB 再套 mean/std。
    const Image8 input = src.order == ColorOrder::BGR ? swapRedBlue(src) : src;

    result.originalWidth = input.width;
    result.originalHeight = input.height;

    double ratio = limitRatio(input.width, input.height, config);
    int newH = static_cast<int>(input.height * ratio);
    int newW = static_cast<int>(input.width * ratio);
    if (std::max(newH, newW) > config.detMaxSideLimit) {
        ratio = static_cast<double>(config.detMaxSideLimit) / std::max(newH, newW);
        newH = static_cast<int>(newH * ratio);
        newW = static_cast<int>(newW * ratio);
    }

    newH = std::max(roundHalfToEven(static_cast<double>(newH) / kDetAlign) * kDetAlign, kDetAlign);
    newW = std::max(roundHalfToEven(static_cast<double>(newW) / kDetAlign) * kDetAlign, kDetAlign);

    const Image8 resized = resize(input, newW, newH, Interp::Linear);
    if (resized.empty()) return result;

    result.tensor.dims = {1, 3, newH, newW};
    fillChannels(result.tensor, resized, newW, newH, kDetMean, kDetStd, kDetScale);
    return result;
}

RecPreprocessResult preprocessRec(const std::vector<Image8>& crops) {
    std::vector<const Image8*> pointers;
    pointers.reserve(crops.size());
    for (const Image8& crop : crops) pointers.push_back(&crop);
    return preprocessRec(pointers);
}

RecPreprocessResult preprocessRec(const std::vector<const Image8*>& crops) {
    RecPreprocessResult result;

    std::vector<Image8> resized;
    resized.reserve(crops.size());
    int maxWidth = 0;

    for (const Image8* crop : crops) {
        if (crop == nullptr || crop->empty()) continue;
        const Image8 rgb = crop->order == ColorOrder::BGR ? swapRedBlue(*crop) : *crop;
        const double aspect = rgb.height > 0 ? static_cast<double>(rgb.width) / rgb.height : 1.0;
        const int newW = std::min(static_cast<int>(std::ceil(kRecHeight * aspect)), kRecMaxWidth);

        Image8 scaled = resize(rgb, newW, kRecHeight, Interp::Linear);
        if (scaled.empty()) continue;
        maxWidth = std::max(maxWidth, newW);
        resized.push_back(std::move(scaled));
    }

    if (resized.empty() || maxWidth <= 0) return result;

    const int channelSize = kRecHeight * maxWidth;
    const int batch = static_cast<int>(resized.size());
    result.batch = batch;
    result.height = kRecHeight;
    result.width = maxWidth;
    result.tensor.dims = {batch, 3, kRecHeight, maxWidth};
    result.tensor.data.assign(static_cast<size_t>(batch) * 3u * static_cast<size_t>(channelSize), 0.0f);

    // 补边区保持 0.0（归一化后即灰），与 Android 的 Scalar(0.0) 一致。
    for (int b = 0; b < batch; ++b) {
        const Image8& image = resized[static_cast<size_t>(b)];
        for (int y = 0; y < kRecHeight; ++y) {
            const uint8_t* row = image.row(y);
            for (int x = 0; x < image.width; ++x) {
                const size_t index = static_cast<size_t>(y) * static_cast<size_t>(maxWidth) +
                                     static_cast<size_t>(x);
                for (int c = 0; c < 3; ++c) {
                    const double value = row[x * 3 + c] / 127.5 - 1.0;
                    const size_t offset = (static_cast<size_t>(b) * 3u + static_cast<size_t>(c)) *
                                              static_cast<size_t>(channelSize) + index;
                    result.tensor.data[offset] = static_cast<float>(value);
                }
            }
        }
    }
    return result;
}

}  // namespace ocr
