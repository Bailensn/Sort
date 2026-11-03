#include "table/ColumnCleaner.hpp"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <string>
#include <vector>

namespace ocr {
namespace {

constexpr double kMaxNoisyFill = 0.6;
constexpr double kMaxLongValueRatio = 0.2;
constexpr double kMaxNoiseLetterRatio = 0.15;
constexpr double kMaxEmptyRatio = 0.6;
constexpr double kJunkValueRatio = 0.2;
constexpr double kLetterColumnRatio = 0.5;
// 门槛太紧会把真列整根抹掉：一列 30 个数字里有 3 个认错就成了「垃圾列」，
// 丢的是整列姓名或总分。宁可留下几个脏格，也不能丢列。
constexpr double kMaxNonNumericRatio = 0.35;
constexpr double kMinChineseRatio = 0.8;

std::string trim(const std::string& value) {
    size_t begin = 0;
    size_t end = value.size();
    while (begin < end && std::isspace(static_cast<unsigned char>(value[begin]))) ++begin;
    while (end > begin && std::isspace(static_cast<unsigned char>(value[end - 1]))) --end;
    return value.substr(begin, end - begin);
}

/// 够用的 UTF-8 解码：只关心码点，不校验合法性。
std::vector<uint32_t> decode(const std::string& text) {
    std::vector<uint32_t> out;
    out.reserve(text.size());
    for (size_t i = 0; i < text.size();) {
        const unsigned char ch = static_cast<unsigned char>(text[i]);
        if (ch < 0x80) {
            out.push_back(ch);
            ++i;
        } else if ((ch & 0xE0) == 0xC0 && i + 1 < text.size()) {
            out.push_back(((ch & 0x1Fu) << 6) | (static_cast<unsigned char>(text[i + 1]) & 0x3Fu));
            i += 2;
        } else if ((ch & 0xF0) == 0xE0 && i + 2 < text.size()) {
            out.push_back(((ch & 0x0Fu) << 12) |
                          ((static_cast<unsigned char>(text[i + 1]) & 0x3Fu) << 6) |
                          (static_cast<unsigned char>(text[i + 2]) & 0x3Fu));
            i += 3;
        } else if ((ch & 0xF8) == 0xF0 && i + 3 < text.size()) {
            out.push_back(((ch & 0x07u) << 18) |
                          ((static_cast<unsigned char>(text[i + 1]) & 0x3Fu) << 12) |
                          ((static_cast<unsigned char>(text[i + 2]) & 0x3Fu) << 6) |
                          (static_cast<unsigned char>(text[i + 3]) & 0x3Fu));
            i += 4;
        } else {
            ++i;
        }
    }
    return out;
}

bool isLineLike(uint32_t cp) {
    switch (cp) {
        case 0x4E00:  // 一
        case 0x4E8C:  // 二
        case 0x4E09:  // 三
        case 0x5341:  // 十
        case 0x4E28:  // 丨
        case 0xFF5C:  // ｜
        case 0x2014:  // —
        case 0x2013:  // –
        case 0x00B7:  // ·
        case '-': case '_': case '.': case '|': case '\'': case '`':
            return true;
        default:
            return false;
    }
}

bool isAsciiLetter(uint32_t cp) {
    return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z');
}

bool noChinese(const std::string& raw) {
    for (uint32_t cp : decode(raw)) {
        if (isLineLike(cp)) continue;
        if (cp > 0x2E80) return false;
    }
    return true;
}

bool hasChinese(const std::string& raw) {
    for (uint32_t cp : decode(raw)) {
        if (isLineLike(cp)) continue;
        if (cp > 0x2E80) return true;
    }
    return false;
}

bool hasLetter(const std::string& raw) {
    for (uint32_t cp : decode(raw)) {
        if (isAsciiLetter(cp)) return true;
    }
    return false;
}

bool looksLikeStrayMark(const std::string& value) {
    if (value.size() > 3 || !noChinese(value)) return false;
    for (char ch : value) {
        if (ch == '.' || ch == '-') continue;
        if (ch < '0' || ch > '9') return true;
    }
    return false;
}

bool looksLikeBackgroundText(const std::string& value) {
    return value.size() >= 3 && noChinese(value) && hasLetter(value);
}

std::vector<std::string> columnValues(const TableRows& rows, int column) {
    std::vector<std::string> values;
    for (const auto& row : rows) {
        if (column < 0 || column >= static_cast<int>(row.size())) continue;
        const std::string value = trim(row[static_cast<size_t>(column)]);
        if (!value.empty()) values.push_back(value);
    }
    return values;
}

TableRows blankColumns(const TableRows& rows, const std::vector<bool>& drop) {
    TableRows out = rows;
    for (auto& row : out) {
        for (size_t column = 0; column < row.size() && column < drop.size(); ++column) {
            if (drop[column]) row[column].clear();
        }
    }
    return out;
}

}  // namespace

TrimmedColumns trimColumns(const TableRows& rows) {
    TrimmedColumns result;
    result.rows = rows;
    if (rows.empty()) return result;

    size_t width = 0;
    for (const auto& row : rows) width = std::max(width, row.size());
    if (width <= 1) return result;

    TableRows grid;
    grid.reserve(rows.size());
    for (const auto& row : rows) {
        std::vector<std::string> line(width);
        for (size_t i = 0; i < width; ++i) line[i] = trim(i < row.size() ? row[i] : std::string());
        grid.push_back(std::move(line));
    }

    int start = 0;
    while (start < static_cast<int>(width) - 1) {
        const std::vector<std::string> values = columnValues(grid, start);
        if (values.empty()) {
            ++start;
            continue;
        }
        if (values.size() == 1 && looksLikeStrayMark(values[0])) {
            ++start;
            continue;
        }
        break;
    }

    int end = static_cast<int>(width);
    while (end - start > 1) {
        const int column = end - 1;
        const std::vector<std::string> values = columnValues(grid, column);
        if (values.empty()) {
            --end;
            continue;
        }
        const double emptyRatio = 1.0 - static_cast<double>(values.size()) / grid.size();
        bool allNoChinese = true;
        for (const auto& value : values) {
            if (!noChinese(value)) {
                allNoChinese = false;
                break;
            }
        }
        int junk = 0;
        for (const auto& value : values) {
            if (looksLikeBackgroundText(value)) ++junk;
        }
        const double junkRatio = static_cast<double>(junk) / values.size();

        int substantial = 0;
        int letters = 0;
        for (const auto& value : values) {
            if (value.size() < 2) continue;
            ++substantial;
            if (hasLetter(value)) ++letters;
        }
        const double letterRatio =
            substantial == 0 ? 0.0 : static_cast<double>(letters) / substantial;

        if (allNoChinese &&
            (emptyRatio >= kMaxEmptyRatio || junkRatio >= kJunkValueRatio ||
             letterRatio >= kLetterColumnRatio)) {
            --end;
            continue;
        }
        break;
    }

    TableRows trimmed;
    trimmed.reserve(grid.size());
    for (const auto& line : grid) {
        trimmed.emplace_back(line.begin() + start, line.begin() + end);
    }
    result.rows = std::move(trimmed);
    result.droppedLeading = start;
    return result;
}

TableRows cleanNoisyColumns(const TableRows& rows) {
    if (rows.empty()) return rows;
    size_t width = 0;
    for (const auto& row : rows) width = std::max(width, row.size());
    if (width <= 1) return rows;

    std::vector<bool> noisy(width, false);
    for (size_t column = 0; column < width; ++column) {
        const std::vector<std::string> values = columnValues(rows, static_cast<int>(column));
        const double filledRatio = static_cast<double>(values.size()) / rows.size();
        if (filledRatio > kMaxNoisyFill) continue;

        int longValues = 0;
        int letters = 0;
        bool allNoChinese = true;
        for (const auto& value : values) {
            if (value.size() > 3) ++longValues;
            if (hasLetter(value)) ++letters;
            if (!noChinese(value)) allNoChinese = false;
        }
        const double letterRatio = values.empty() ? 0.0 : static_cast<double>(letters) / values.size();
        noisy[column] = (allNoChinese || letterRatio >= kMaxNoiseLetterRatio) &&
                        longValues <= static_cast<int>(values.size() * kMaxLongValueRatio);
    }

    int start = 0;
    while (start < static_cast<int>(width)) {
        if (!noisy[static_cast<size_t>(start)]) {
            ++start;
            continue;
        }
        int end = start;
        while (end + 1 < static_cast<int>(width) && noisy[static_cast<size_t>(end + 1)]) ++end;
        // 单列不算噪声：真数据里「只有部分行有值」的列很常见
        if (end == start) noisy[static_cast<size_t>(start)] = false;
        start = end + 1;
    }

    bool any = false;
    for (bool flag : noisy) any = any || flag;
    if (!any) return rows;
    return blankColumns(rows, noisy);
}

TableRows cleanBadColumns(const TableRows& rows) {
    if (rows.empty()) return rows;
    size_t width = 0;
    for (const auto& row : rows) width = std::max(width, row.size());
    if (width <= 1) return rows;

    struct Stats {
        double filled = 0;
        int numeric = 0;
        int chinese = 0;
        int count = 0;
    };
    std::vector<Stats> stats(width);
    for (size_t column = 0; column < width; ++column) {
        const std::vector<std::string> values = columnValues(rows, static_cast<int>(column));
        Stats& stat = stats[column];
        stat.filled = static_cast<double>(values.size()) / rows.size();
        stat.count = static_cast<int>(values.size());
        for (const auto& value : values) {
            double ignored = 0;
            if (parseNumber(value, ignored)) ++stat.numeric;
            if (hasChinese(value)) ++stat.chinese;
        }
    }

    size_t nameColumn = 0;
    int best = -1;
    for (size_t column = 0; column < width; ++column) {
        if (stats[column].chinese > best) {
            best = stats[column].chinese;
            nameColumn = column;
        }
    }

    std::vector<bool> bad(width, false);
    for (size_t column = 0; column < width; ++column) {
        const Stats& stat = stats[column];
        if (column == nameColumn || stat.filled < 0.05 || stat.count == 0) continue;
        const double nonNumericRatio = 1.0 - static_cast<double>(stat.numeric) / stat.count;
        const double chineseRatio = static_cast<double>(stat.chinese) / stat.count;
        bad[column] = nonNumericRatio > kMaxNonNumericRatio && chineseRatio < kMinChineseRatio;
    }

    bool any = false;
    for (bool flag : bad) any = any || flag;
    if (!any) return rows;
    return blankColumns(rows, bad);
}

}  // namespace ocr
