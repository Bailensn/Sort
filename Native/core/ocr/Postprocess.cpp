#include "Postprocess.hpp"

#include <algorithm>
#include <cmath>

namespace ocr {
namespace {

constexpr double kEps = 1e-6;
constexpr double kPi = 3.14159265;
constexpr double kArcTolerance = 0.25;
constexpr float kRowThresholdY = 10.0f;

double distance(const PointF& a, const PointF& b) {
    return std::hypot(static_cast<double>(b.x - a.x), static_cast<double>(b.y - a.y));
}

double signedArea(const std::vector<PointF>& points) {
    double sum = 0.0;
    for (size_t i = 0; i < points.size(); ++i) {
        const PointF& p1 = points[i];
        const PointF& p2 = points[(i + 1) % points.size()];
        sum += static_cast<double>(p1.x) * p2.y - static_cast<double>(p2.x) * p1.y;
    }
    return sum / 2.0;
}

double perimeter(const std::vector<PointF>& points) {
    double length = 0.0;
    for (size_t i = 0; i < points.size(); ++i) {
        length += distance(points[i], points[(i + 1) % points.size()]);
    }
    return length;
}

double arcStepAngle(double d) {
    double ratio = 1.0 - kArcTolerance / d;
    ratio = std::max(-1.0, std::min(1.0, ratio));
    const double step = 2.0 * std::acos(ratio);
    return (std::isfinite(step) && step > kEps) ? step : kPi / 8.0;
}

void appendIfFar(std::vector<PointF>& output, const PointF& point) {
    if (output.empty() || distance(output.back(), point) > kEps) {
        output.push_back(point);
    }
}

// ---- UTF-8 辅助：CellText 要按「字」而不是按字节判断 ----

std::vector<char32_t> decodeUtf8(const std::string& text) {
    std::vector<char32_t> runes;
    size_t i = 0;
    while (i < text.size()) {
        const unsigned char lead = static_cast<unsigned char>(text[i]);
        char32_t code = 0;
        int extra = 0;
        if (lead < 0x80) {
            code = lead;
        } else if ((lead & 0xE0) == 0xC0) {
            code = lead & 0x1F;
            extra = 1;
        } else if ((lead & 0xF0) == 0xE0) {
            code = lead & 0x0F;
            extra = 2;
        } else if ((lead & 0xF8) == 0xF0) {
            code = lead & 0x07;
            extra = 3;
        } else {
            runes.push_back(0xFFFD);
            ++i;
            continue;
        }
        if (i + static_cast<size_t>(extra) >= text.size()) {
            runes.push_back(0xFFFD);
            break;
        }
        for (int k = 1; k <= extra; ++k) {
            const unsigned char next = static_cast<unsigned char>(text[i + static_cast<size_t>(k)]);
            if ((next & 0xC0) != 0x80) {
                code = 0xFFFD;
                break;
            }
            code = (code << 6) | (next & 0x3F);
        }
        runes.push_back(code);
        i += static_cast<size_t>(extra) + 1;
    }
    return runes;
}

std::string encodeUtf8(const std::vector<char32_t>& runes) {
    std::string out;
    for (char32_t code : runes) {
        if (code <= 0x7F) {
            out.push_back(static_cast<char>(code));
        } else if (code <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (code >> 6)));
            out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
        } else if (code <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (code >> 12)));
            out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (code >> 18)));
            out.push_back(static_cast<char>(0x80 | ((code >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
        }
    }
    return out;
}

bool isCjk(char32_t code) {
    return (code >= 0x3400 && code <= 0x9FFF) || (code >= 0xF900 && code <= 0xFAFF);
}

bool isEdgeNoise(char32_t code) {
    static const std::vector<char32_t> kNoise = {
        '|', 0xFF5C, 0x4E28, '_', 0x2014, 0x2013, '-', 0x00B7, 0x2022, '.', ',',
        0xFF0C, 0x3002, ':', 0xFF1A, ';', 0xFF1B, '\'', '"', '`', '~', '^',
    };
    return std::find(kNoise.begin(), kNoise.end(), code) != kNoise.end();
}

bool isLineLike(char32_t code) {
    static const std::vector<char32_t> kLine = {'|', 0xFF5C, 0x4E28, '_', 0x2014,
                                                0x2013, '-', '1', 'l', 'I'};
    return std::find(kLine.begin(), kLine.end(), code) != kLine.end();
}

bool isSpace(char32_t code) {
    return code == ' ' || code == '\t' || code == '\n' || code == '\r' || code == 0x3000;
}

}  // namespace

Box orderQuad(const PointF points[4]) {
    std::vector<PointF> sorted(points, points + 4);
    std::sort(sorted.begin(), sorted.end(), [](const PointF& a, const PointF& b) {
        return a.x < b.x;
    });

    // 左边两点里 y 小的在上，右边两点同理。
    const PointF topLeft = sorted[1].y > sorted[0].y ? sorted[0] : sorted[1];
    const PointF bottomLeft = sorted[1].y > sorted[0].y ? sorted[1] : sorted[0];
    const PointF topRight = sorted[3].y > sorted[2].y ? sorted[2] : sorted[3];
    const PointF bottomRight = sorted[3].y > sorted[2].y ? sorted[3] : sorted[2];

    Box box;
    box.points[0] = topLeft;
    box.points[1] = topRight;
    box.points[2] = bottomRight;
    box.points[3] = bottomLeft;
    return box;
}

std::vector<PointF> unclip(const std::vector<PointF>& points, double unclipRatio) {
    if (points.size() < 3) return points;

    const double area = std::fabs(signedArea(points));
    const double length = perimeter(points);
    if (!std::isfinite(area) || !std::isfinite(length) || area <= kEps || length <= kEps) {
        return points;
    }

    const double d = area * unclipRatio / length;
    if (!std::isfinite(d) || d <= kEps) return points;

    const bool clockwise = signedArea(points) > 0.0;
    std::vector<PointF> normals;
    normals.reserve(points.size());
    for (size_t i = 0; i < points.size(); ++i) {
        const PointF& start = points[i];
        const PointF& end = points[(i + 1) % points.size()];
        const double dx = end.x - start.x;
        const double dy = end.y - start.y;
        const double len = std::hypot(dx, dy);
        if (!std::isfinite(len) || len <= kEps) return points;
        normals.push_back(clockwise ? PointF{static_cast<float>(dy / len),
                                             static_cast<float>(-dx / len)}
                                    : PointF{static_cast<float>(-dy / len),
                                             static_cast<float>(dx / len)});
    }

    std::vector<PointF> expanded;
    for (size_t i = 0; i < points.size(); ++i) {
        const PointF& center = points[i];
        const PointF& from = normals[(i + normals.size() - 1) % normals.size()];
        const PointF& to = normals[i];

        double startAngle = std::atan2(from.y, from.x);
        double endAngle = std::atan2(to.y, to.x);
        if (clockwise) {
            while (endAngle < startAngle) endAngle += 2.0 * kPi;
        } else {
            while (endAngle > startAngle) endAngle -= 2.0 * kPi;
        }

        const double sweep = endAngle - startAngle;
        const int steps = std::max(1, static_cast<int>(std::ceil(std::fabs(sweep) / arcStepAngle(d))));
        for (int step = 0; step <= steps; ++step) {
            const double angle = startAngle + sweep * step / steps;
            appendIfFar(expanded,
                        PointF{static_cast<float>(center.x + std::cos(angle) * d),
                               static_cast<float>(center.y + std::sin(angle) * d)});
        }
    }
    return expanded.size() >= 3 ? expanded : points;
}

std::vector<std::pair<std::string, float>> ctcDecode(const Tensor& output,
                                                     const std::vector<std::string>& charset) {
    std::vector<std::pair<std::string, float>> results;
    if (output.dims.size() != 3) return results;

    const int batch = static_cast<int>(output.dims[0]);
    const int timeSteps = static_cast<int>(output.dims[1]);
    const int classes = static_cast<int>(output.dims[2]);
    if (batch <= 0 || timeSteps <= 0 || classes <= 0) return results;

    results.reserve(static_cast<size_t>(batch));
    for (int b = 0; b < batch; ++b) {
        const size_t base = static_cast<size_t>(b) * static_cast<size_t>(timeSteps) *
                            static_cast<size_t>(classes);

        std::string text;
        std::vector<float> kept;
        int previous = -1;
        for (int t = 0; t < timeSteps; ++t) {
            const size_t offset = base + static_cast<size_t>(t) * static_cast<size_t>(classes);
            int best = 0;
            float bestValue = output.data[offset];
            for (int c = 1; c < classes; ++c) {
                const float value = output.data[offset + static_cast<size_t>(c)];
                if (value > bestValue) {
                    bestValue = value;
                    best = c;
                }
            }
            if (best != 0 && best != previous) {
                const int index = best - 1;
                if (index >= 0 && index < static_cast<int>(charset.size())) {
                    text += charset[static_cast<size_t>(index)];
                    kept.push_back(bestValue);
                }
            }
            previous = best;
        }

        float confidence = 0.0f;
        if (!kept.empty()) {
            double sum = 0.0;
            for (float value : kept) sum += value;
            confidence = static_cast<float>(sum / static_cast<double>(kept.size()));
        }
        results.emplace_back(text, confidence);
    }
    return results;
}

std::string cleanCellText(const std::string& raw) {
    if (raw.empty()) return "";

    std::vector<char32_t> runes = decodeUtf8(raw);

    size_t begin = 0;
    size_t end = runes.size();
    while (begin < end && isSpace(runes[begin])) ++begin;
    while (end > begin && isSpace(runes[end - 1])) --end;
    // 「84.5」里的点是数字的一部分，不能当两端的线头噪声去掉
    auto strippable = [&runes](size_t index, bool leftSide) {
        const char32_t ch = runes[index];
        if (ch == '.' || ch == ',') {
            const size_t neighbour = leftSide ? index + 1 : index - 1;
            if (neighbour < runes.size()) {
                const char32_t next = runes[neighbour];
                if (next >= '0' && next <= '9') return false;
            }
        }
        return isEdgeNoise(ch);
    };
    while (begin < end && strippable(begin, true)) ++begin;
    while (end > begin && strippable(end - 1, false)) --end;
    if (begin >= end) return "";

    // 汉字之间的空格是识别噪声，英文单词之间的要留。
    std::vector<char32_t> builder;
    for (size_t i = begin; i < end; ++i) {
        if (runes[i] == ' ') {
            const bool hasPrev = !builder.empty();
            const bool hasNext = i + 1 < end;
            if (hasPrev && hasNext) {
                const char32_t previous = builder.back();
                const char32_t next = runes[i + 1];
                if (isCjk(previous) && isCjk(next)) continue;
                // 「84. 5」：小数点后多出的空格会把数字拆散
                if ((previous == '.' || previous == ',') && next >= '0' && next <= '9') continue;
                if (previous >= '0' && previous <= '9' && (next == '.' || next == ',')) continue;
            }
        }
        builder.push_back(runes[i]);
    }

    while (!builder.empty() && isSpace(builder.back())) builder.pop_back();
    return encodeUtf8(builder);
}

bool looksLikeLine(const std::string& raw) {
    const std::vector<char32_t> runes = decodeUtf8(raw);
    size_t begin = 0;
    size_t end = runes.size();
    while (begin < end && isSpace(runes[begin])) ++begin;
    while (end > begin && isSpace(runes[end - 1])) --end;
    if (begin >= end || end - begin > 2) return false;
    for (size_t i = begin; i < end; ++i) {
        if (!isLineLike(runes[i])) return false;
    }
    return true;
}

void sortInReadingOrder(std::vector<Box>& boxes) {
    if (boxes.size() <= 1) return;

    std::sort(boxes.begin(), boxes.end(), [](const Box& a, const Box& b) {
        if (a.points[0].y != b.points[0].y) return a.points[0].y < b.points[0].y;
        return a.points[0].x < b.points[0].x;
    });

    // 相邻两行 y 差小于阈值时，左边那个排前面。
    for (size_t i = 0; i + 1 < boxes.size(); ++i) {
        size_t j = i;
        while (true) {
            const Box& next = boxes[j + 1];
            const Box& curr = boxes[j];
            if (std::fabs(next.points[0].y - curr.points[0].y) < kRowThresholdY &&
                next.points[0].x < curr.points[0].x) {
                std::swap(boxes[j], boxes[j + 1]);
                if (j == 0) break;
                --j;
            } else {
                break;
            }
        }
    }
}

}  // namespace ocr
