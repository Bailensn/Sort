#include "table/TableLogic.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <set>

namespace ocr {
namespace {

/// 分数有一位小数，求和误差容忍到这个量级。
constexpr double kSumEpsilon = 0.01;
constexpr double kNumericRatio = 0.6;
constexpr int kMinSubjectsForSum = 2;
constexpr double kMinSubjectFill = 0.75;
constexpr double kMinTotalNumericRatio = 0.6;
constexpr float kRecomputeTotalConfidence = 0.9f;

/// 算出来的和跟 OCR 出来的总分差到这个倍数，就是某一科读错了，别再写进去。
constexpr double kSumOutlierRatio = 2.5;

/// 越界写会把整张表写坏：列号来自表头，行可能比它短。
void setCell(std::vector<std::string>& row, int column, const std::string& value) {
    if (column < 0) return;
    if (static_cast<size_t>(column) >= row.size()) row.resize(static_cast<size_t>(column) + 1);
    row[static_cast<size_t>(column)] = value;
}

std::string trim(const std::string& value) {
    size_t begin = 0;
    size_t end = value.size();
    while (begin < end && std::isspace(static_cast<unsigned char>(value[begin]))) ++begin;
    while (end > begin && std::isspace(static_cast<unsigned char>(value[end - 1]))) --end;
    return value.substr(begin, end - begin);
}

std::string cellAt(const TableRows& rows, int row, int column) {
    if (row < 0 || row >= static_cast<int>(rows.size())) return {};
    const auto& line = rows[static_cast<size_t>(row)];
    if (column < 0 || column >= static_cast<int>(line.size())) return {};
    return line[static_cast<size_t>(column)];
}

bool contains(const std::string& text, const char* needle) {
    return text.find(needle) != std::string::npos;
}

bool isNumericColumn(const TableRows& rows, int column) {
    int filled = 0;
    int numeric = 0;
    for (int row = 0; row < static_cast<int>(rows.size()); ++row) {
        const std::string value = trim(cellAt(rows, row, column));
        if (value.empty()) continue;
        ++filled;
        double ignored = 0;
        if (parseNumber(value, ignored)) ++numeric;
    }
    if (filled == 0) return false;
    return static_cast<double>(numeric) / filled >= kNumericRatio;
}

bool isConstantColumn(const TableRows& rows, int column) {
    std::vector<std::string> values;
    for (int row = 0; row < static_cast<int>(rows.size()); ++row) {
        const std::string value = trim(cellAt(rows, row, column));
        if (!value.empty()) values.push_back(value);
    }
    if (values.size() < static_cast<size_t>(rows.size() * 0.8)) return false;
    std::set<std::string> distinct(values.begin(), values.end());
    return distinct.size() == 1;
}

std::vector<std::string> nonEmptyValues(const TableRows& rows, int column) {
    std::vector<std::string> values;
    for (int row = 0; row < static_cast<int>(rows.size()); ++row) {
        const std::string value = trim(cellAt(rows, row, column));
        if (!value.empty()) values.push_back(value);
    }
    return values;
}

bool isSequenceColumn(const TableRows& rows, int column) {
    const std::vector<std::string> values = nonEmptyValues(rows, column);
    if (values.size() < 3) return false;
    for (const auto& value : values) {
        double ignored = 0;
        if (value.size() > 3 || !parseNumber(value, ignored)) return false;
    }
    int steps = 0;
    int ones = 0;
    for (size_t index = 1; index < values.size(); ++index) {
        double previous = 0;
        double current = 0;
        if (!parseNumber(values[index - 1], previous) || !parseNumber(values[index], current)) {
            return false;
        }
        ++steps;
        if (std::abs(current - previous - 1.0) <= kSumEpsilon) ++ones;
    }
    return steps > 0 && static_cast<double>(ones) / steps >= 0.8;
}

bool isIdentifierColumn(const TableRows& rows, int column) {
    const std::vector<std::string> values = nonEmptyValues(rows, column);
    if (values.size() < static_cast<size_t>(rows.size() * 0.7)) return false;
    for (const auto& value : values) {
        if (value.size() < 8) return false;
        for (char ch : value) {
            if (ch < '0' || ch > '9') return false;
        }
    }
    return true;
}

std::vector<int> sequenceBreaks(const TableRows& rows, int column) {
    std::vector<std::pair<int, double>> values;
    for (int row = 0; row < static_cast<int>(rows.size()); ++row) {
        const std::string raw = trim(cellAt(rows, row, column));
        if (raw.empty()) continue;
        double value = 0;
        if (!parseNumber(raw, value)) return {};
        values.emplace_back(row, value);
    }
    if (values.size() < 3) return {};
    std::vector<int> breaks;
    for (size_t index = 1; index < values.size(); ++index) {
        if (std::abs(values[index].second - values[index - 1].second - 1.0) > kSumEpsilon) {
            breaks.push_back(values[index].first);
        }
    }
    return breaks;
}

bool hasLatinLetters(const TableRows& rows, int column) {
    for (int row = 0; row < static_cast<int>(rows.size()); ++row) {
        for (char ch : cellAt(rows, row, column)) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) return true;
        }
    }
    return false;
}

struct ColumnPair {
    int total = 0;
    std::vector<int> subjects;
};

ColumnPair resolveExplicit(const TableHeader* header) {
    ColumnPair pair;
    pair.total = -1;
    if (header == nullptr) return pair;
    const auto total = header->totalColumn();
    if (!total.has_value()) return pair;
    pair.total = *total;
    pair.subjects = header->subjectColumns();
    if (pair.subjects.empty()) pair.total = -1;
    if (std::find(pair.subjects.begin(), pair.subjects.end(), pair.total) != pair.subjects.end()) {
        pair.total = -1;
    }
    return pair;
}

/// 自动认出的表头要拿数据验一遍：科目列不像分数就不采信，免得按错的角色改分。
bool headerLooksPlausible(const TableRows& rows, const TableHeader& header, const ColumnPair& pair) {
    if (!header.fromImage || pair.total < 0 || pair.subjects.empty()) return true;

    int totalFilled = 0;
    int totalNumeric = 0;
    for (int row = 0; row < static_cast<int>(rows.size()); ++row) {
        const std::string value = trim(cellAt(rows, row, pair.total));
        if (value.empty()) continue;
        ++totalFilled;
        double number = 0;
        if (parseNumber(value, number) && number >= 0.0 && number <= 999.0) ++totalNumeric;
    }
    if (totalFilled > 0 && static_cast<double>(totalNumeric) / totalFilled < 0.8) return false;

    for (int column : pair.subjects) {
        int filled = 0;
        int numeric = 0;
        for (int row = 0; row < static_cast<int>(rows.size()); ++row) {
            const std::string value = trim(cellAt(rows, row, column));
            if (value.empty()) continue;
            ++filled;
            double number = 0;
            if (parseNumber(value, number) && number >= 0.0 && number <= 150.0) ++numeric;
        }
        if (filled > 0 && static_cast<double>(numeric) / filled < 0.6) return false;
    }
    return true;
}

ColumnPair findTotalColumn(const TableRows& rows, int columns, const std::vector<bool>& numeric,
                           const std::vector<bool>& constant, const std::vector<bool>& sequences,
                           const std::vector<bool>& identifiers) {
    ColumnPair pair;
    pair.total = -1;
    if (columns < 3) return pair;

    std::vector<double> fillRatio(static_cast<size_t>(columns), 0.0);
    std::vector<double> numericRatio(static_cast<size_t>(columns), 0.0);
    for (int column = 0; column < columns; ++column) {
        int filled = 0;
        for (int row = 0; row < static_cast<int>(rows.size()); ++row) {
            if (!trim(cellAt(rows, row, column)).empty()) ++filled;
        }
        fillRatio[static_cast<size_t>(column)] =
            rows.empty() ? 0.0 : static_cast<double>(filled) / static_cast<double>(rows.size());

        const std::vector<std::string> values = nonEmptyValues(rows, column);
        int hits = 0;
        for (const auto& value : values) {
            double ignored = 0;
            if (parseNumber(value, ignored)) ++hits;
        }
        numericRatio[static_cast<size_t>(column)] =
            values.empty() ? 0.0 : static_cast<double>(hits) / static_cast<double>(values.size());
    }

    std::vector<bool> eligible(static_cast<size_t>(columns), false);
    for (int column = 0; column < columns; ++column) {
        const size_t index = static_cast<size_t>(column);
        eligible[index] = numeric[index] && !constant[index] && !sequences[index] &&
                          !identifiers[index] && fillRatio[index] >= kMinSubjectFill;
    }

    // 业务规则：总分默认是最右一列，除非它带拉丁字母或整列没有一个数字。
    const int last = columns - 1;
    const std::vector<std::string> lastValues = nonEmptyValues(rows, last);
    bool lastIsScore = !hasLatinLetters(rows, last);
    if (lastIsScore && !lastValues.empty()) {
        lastIsScore = false;
        for (const auto& value : lastValues) {
            double ignored = 0;
            if (parseNumber(value, ignored)) {
                lastIsScore = true;
                break;
            }
        }
    }

    int total = last;
    if (!lastIsScore) {
        int candidate = last;
        while (candidate > 0 &&
               !(fillRatio[static_cast<size_t>(candidate)] >= 0.2 &&
                 numericRatio[static_cast<size_t>(candidate)] >= kMinTotalNumericRatio)) {
            --candidate;
        }
        total = candidate;
    }
    if (total <= 0) return pair;

    int end = total - 1;
    while (end > 0 && !eligible[static_cast<size_t>(end)]) --end;
    if (end <= 0) return pair;
    int begin = end;
    while (begin - 1 >= 0 && eligible[static_cast<size_t>(begin - 1)]) --begin;
    if (end - begin + 1 < 2) return pair;

    pair.total = total;
    for (int column = begin; column <= end; ++column) pair.subjects.push_back(column);
    return pair;
}

std::string formatNumber(double value) {
    const double rounded = std::round(value * 10.0) / 10.0;
    if (rounded == std::floor(rounded)) {
        char buffer[32];
        std::snprintf(buffer, sizeof(buffer), "%lld", static_cast<long long>(rounded));
        return buffer;
    }
    char buffer[64];
    std::snprintf(buffer, sizeof(buffer), "%.1f", rounded);
    return buffer;
}

float confidenceAt(const TableConfidence* confidences, int row, int column) {
    if (confidences == nullptr) return 0.0f;
    if (row < 0 || row >= static_cast<int>(confidences->size())) return 0.0f;
    const auto& line = (*confidences)[static_cast<size_t>(row)];
    if (column < 0 || column >= static_cast<int>(line.size())) return 0.0f;
    return line[static_cast<size_t>(column)];
}

struct Edit {
    int column = 0;
    std::string value;
};

std::optional<Edit> fixSingleCell(std::vector<std::string>& row, int rowIndex,
                                  const std::vector<int>& subjects, int totalColumn, double sum,
                                  double total, const TableConfidence* confidences) {
    std::vector<Edit> candidates;
    std::vector<int> targets = subjects;
    targets.push_back(totalColumn);

    for (int column : targets) {
        if (column < 0 || column >= static_cast<int>(row.size())) continue;
        const std::string raw = trim(row[static_cast<size_t>(column)]);
        double parsed = 0;
        if (raw.empty() || !parseNumber(raw, parsed)) continue;

        std::vector<std::string> edits;
        for (size_t position = 0; position < raw.size(); ++position) {
            for (char digit = '0'; digit <= '9'; ++digit) {
                if (digit == raw[position]) continue;
                std::string candidate = raw;
                candidate[position] = digit;
                edits.push_back(candidate);
            }
        }
        // 小数点被吃掉：945 其实是 94.5。补一个点再看和能不能对上。
        if (raw.find('.') == std::string::npos) {
            for (size_t position = 1; position < raw.size(); ++position) {
                if (raw[position - 1] < '0' || raw[position - 1] > '9') continue;
                edits.push_back(raw.substr(0, position) + "." + raw.substr(position));
            }
        }

        for (const auto& candidate : edits) {
            double edited = 0;
            if (!parseNumber(candidate, edited)) continue;
            if (column == totalColumn) {
                if (std::abs(sum - edited) <= kSumEpsilon) candidates.push_back({column, candidate});
            } else {
                const double editedSum = sum - parsed + edited;
                if (std::abs(editedSum - total) <= kSumEpsilon) {
                    candidates.push_back({column, candidate});
                }
            }
        }
    }

    if (candidates.size() == 1) return candidates.front();
    if (candidates.empty() || confidences == nullptr) return std::nullopt;

    std::vector<std::pair<float, Edit>> scored;
    scored.reserve(candidates.size());
    for (const auto& candidate : candidates) {
        scored.emplace_back(confidenceAt(confidences, rowIndex, candidate.column), candidate);
    }
    std::sort(scored.begin(), scored.end(), [](const auto& left, const auto& right) {
        return left.first < right.first;
    });
    const auto& best = scored.front();
    if (scored.size() > 1 && best.first > scored[1].first - 0.02f) return std::nullopt;
    return best.second;
}

}  // namespace

namespace {

constexpr int kMinNumericCells = 3;
constexpr double kMinNumericRatio = 0.5;

char lookalike(unsigned char ch) {
    switch (ch) {
        case 'O': case 'o': case 'D': case 'Q': return '0';
        case 'S': case 's': return '5';
        case 'B': return '8';
        case 'l': case 'I': case '|': case 'i': return '1';
        case 'Z': case 'z': return '2';
        case 'G': case 'g': return '6';
        case 'T': return '7';
        case 'q': return '9';
        default: return 0;
    }
}

/// 把一格里能映射成数字的字符挑出来；凑不成合法数字就返回空。
std::string normalizeCell(const std::string& raw) {
    std::string builder;
    for (size_t i = 0; i < raw.size();) {
        const unsigned char ch = static_cast<unsigned char>(raw[i]);
        if (ch < 0x80) {
            if (ch >= '0' && ch <= '9') {
                builder += static_cast<char>(ch);
            } else if (ch == '.' || ch == ',') {
                builder += '.';
            } else if (const char mapped = lookalike(ch)) {
                builder += mapped;
            }
            ++i;
            continue;
        }
        // 全角：EF BC 80..BF 对应 U+FF00 起，数字是 0x90..0x99，逗号是 0x8C
        if (ch == 0xEF && i + 2 < raw.size() && static_cast<unsigned char>(raw[i + 1]) == 0xBC) {
            const unsigned char third = static_cast<unsigned char>(raw[i + 2]);
            if (third >= 0x90 && third <= 0x99) {
                builder += static_cast<char>('0' + (third - 0x90));
            } else if (third == 0x8C) {
                builder += '.';
            }
            i += 3;
            continue;
        }
        ++i;
    }

    if (builder.empty()) return {};
    bool hasDigit = false;
    for (char ch : builder) {
        if (ch >= '0' && ch <= '9') hasDigit = true;
    }
    if (!hasDigit) return {};

    // 小数点只保留第一个，其余当噪声丢掉
    const size_t firstDot = builder.find('.');
    if (firstDot != std::string::npos) {
        std::string tail;
        for (size_t i = firstDot + 1; i < builder.size(); ++i) {
            if (builder[i] != '.') tail += builder[i];
        }
        builder = builder.substr(0, firstDot + 1) + tail;
    }
    if (!builder.empty() && builder.front() == '.') builder = "0" + builder;
    if (!builder.empty() && builder.back() == '.') builder.pop_back();

    double ignored = 0;
    return parseNumber(builder, ignored) ? builder : std::string();
}

}  // namespace

TableRows repairNumbers(const TableRows& rows) {
    if (rows.empty()) return rows;
    size_t width = 0;
    for (const auto& row : rows) width = std::max(width, row.size());
    if (width == 0) return rows;

    TableRows result = rows;
    for (size_t column = 0; column < width; ++column) {
        std::vector<std::string> values;
        for (const auto& row : result) {
            if (column >= row.size()) continue;
            const std::string value = trim(row[column]);
            if (!value.empty()) values.push_back(value);
        }
        if (static_cast<int>(values.size()) < kMinNumericCells) continue;

        int numeric = 0;
        for (const auto& value : values) {
            double ignored = 0;
            if (parseNumber(value, ignored)) ++numeric;
        }
        if (static_cast<double>(numeric) / values.size() < kMinNumericRatio) continue;

        for (auto& row : result) {
            if (column >= row.size()) continue;
            const std::string value = trim(row[column]);
            if (value.empty()) continue;
            double ignored = 0;
            if (parseNumber(value, ignored)) continue;
            const std::string fixed = normalizeCell(value);
            if (fixed.empty()) continue;
            row[column] = fixed;
        }
    }
    return result;
}

namespace {

bool allDigits(const std::string& value) {
    if (value.empty()) return false;
    for (char ch : value) {
        if (ch < '0' || ch > '9') return false;
    }
    return true;
}

/// 只认能一一映射成数字的字符；有一个映射不了就整格放弃。
std::string mapToDigits(const std::string& raw) {
    std::string builder;
    builder.reserve(raw.size());
    for (char ch : raw) {
        const unsigned char code = static_cast<unsigned char>(ch);
        if (code >= '0' && code <= '9') {
            builder += ch;
            continue;
        }
        if (code >= 0x80) return {};
        const char mapped = lookalike(code);
        if (mapped == 0) return {};
        builder += mapped;
    }
    return builder;
}

}  // namespace

TableRows repairIdentifiers(const TableRows& rows) {
    if (rows.empty()) return rows;
    size_t width = 0;
    for (const auto& row : rows) width = std::max(width, row.size());
    if (width == 0) return rows;

    TableRows result = rows;
    for (size_t column = 0; column < width; ++column) {
        std::vector<std::string> values;
        for (const auto& row : result) {
            if (column >= row.size()) continue;
            const std::string value = trim(row[column]);
            if (!value.empty()) values.push_back(value);
        }
        if (static_cast<int>(values.size()) < kMinNumericCells) continue;

        // 学号长度是固定的：先取纯数字格的众数长度，再只修同样长的格子
        std::vector<size_t> lengths;
        for (const auto& value : values) {
            if (value.size() >= 8 && allDigits(value)) lengths.push_back(value.size());
        }
        if (lengths.empty()) continue;
        if (static_cast<double>(lengths.size()) / values.size() < kMinNumericRatio) continue;
        std::sort(lengths.begin(), lengths.end());
        const size_t typical = lengths[lengths.size() / 2];

        for (auto& row : result) {
            if (column >= row.size()) continue;
            const std::string value = trim(row[column]);
            if (value.size() != typical || allDigits(value)) continue;
            const std::string fixed = mapToDigits(value);
            if (fixed.size() == typical) row[column] = fixed;
        }
    }
    return result;
}

namespace {

bool isSingleLetter(const std::string& value) {
    if (value.size() != 1) return false;
    const unsigned char ch = static_cast<unsigned char>(value[0]);
    return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
}

}  // namespace

TableRows dropStrayLetters(const TableRows& rows) {
    if (rows.empty()) return rows;
    size_t width = 0;
    for (const auto& row : rows) width = std::max(width, row.size());
    if (width == 0) return rows;

    TableRows result = rows;
    for (size_t column = 0; column < width; ++column) {
        std::vector<std::string> values;
        for (const auto& row : result) {
            if (column >= row.size()) continue;
            const std::string value = trim(row[column]);
            if (!value.empty()) values.push_back(value);
        }
        int letters = 0;
        for (const auto& value : values) {
            if (isSingleLetter(value)) ++letters;
        }
        // 整列都是单个字母才算「等级列」；孤零零一个 E 是线头
        if (letters == 0 || (letters >= 3 && letters == static_cast<int>(values.size()))) continue;
        for (auto& row : result) {
            if (column >= row.size()) continue;
            if (isSingleLetter(trim(row[column]))) row[column].clear();
        }
    }
    return result;
}

bool parseNumber(const std::string& raw, double& out) {
    if (raw.empty()) return false;
    size_t index = 0;
    bool digits = false;
    while (index < raw.size() && raw[index] >= '0' && raw[index] <= '9') {
        ++index;
        digits = true;
    }
    if (!digits) return false;
    if (index < raw.size() && raw[index] == '.') {
        ++index;
        const size_t start = index;
        while (index < raw.size() && raw[index] >= '0' && raw[index] <= '9') ++index;
        if (index == start) return false;
    }
    if (index != raw.size()) return false;
    out = std::strtod(raw.c_str(), nullptr);
    return true;
}

std::vector<int> TableHeader::subjectColumns() const {
    std::vector<int> out;
    for (const auto& column : columns) {
        if (column.role == ColumnRole::Subject) out.push_back(column.index);
    }
    std::sort(out.begin(), out.end());
    return out;
}

std::optional<int> TableHeader::totalColumn() const {
    for (const auto& column : columns) {
        if (column.role == ColumnRole::Total) return column.index;
    }
    return std::nullopt;
}

bool TableHeader::hasNames() const {
    for (const auto& column : columns) {
        if (!column.name.empty()) return true;
    }
    return false;
}

namespace {

ColumnRole roleFromName(const std::string& name) {
    if (name.empty()) return ColumnRole::Other;
    // 顺序和 Kotlin 版一致：总分优先，其次学号，再次序号
    if (contains(name, "总分") || contains(name, "总计") || contains(name, "合计") ||
        contains(name, "总成绩") || contains(name, "总计分")) {
        return ColumnRole::Total;
    }
    if (contains(name, "学号") || contains(name, "考号") || contains(name, "准考证")) {
        return ColumnRole::Id;
    }
    if (contains(name, "序号") || contains(name, "编号")) return ColumnRole::Sequence;
    return ColumnRole::Other;
}

bool isHeaderWord(const std::string& text) {
    if (text.empty()) return false;
    return contains(text, "总分") || contains(text, "总计") || contains(text, "合计") ||
           contains(text, "学号") || contains(text, "考号") || contains(text, "序号") ||
           contains(text, "姓名") || contains(text, "名字") || contains(text, "学生") ||
           contains(text, "班级");
}

double numericRatioOfRow(const std::vector<std::string>& row) {
    int values = 0;
    int numeric = 0;
    for (const auto& raw : row) {
        const std::string value = trim(raw);
        if (value.empty()) continue;
        ++values;
        double ignored = 0;
        if (parseNumber(value, ignored)) ++numeric;
    }
    if (values == 0) return 0.0;
    return static_cast<double>(numeric) / values;
}

ColumnRole inferRole(const TableRows& data, int column) {
    const std::vector<std::string> values = nonEmptyValues(data, column);
    if (values.empty()) return ColumnRole::Other;

    bool allIds = true;
    for (const auto& value : values) {
        if (value.size() < 8) {
            allIds = false;
            break;
        }
        for (char ch : value) {
            if (ch < '0' || ch > '9') {
                allIds = false;
                break;
            }
        }
        if (!allIds) break;
    }
    if (allIds) return ColumnRole::Id;

    std::vector<double> numbers;
    numbers.reserve(values.size());
    for (const auto& value : values) {
        double parsed = 0;
        if (parseNumber(value, parsed)) numbers.push_back(parsed);
    }
    if (static_cast<double>(numbers.size()) < static_cast<double>(data.size()) * 0.6) {
        return ColumnRole::Other;
    }

    if (values.size() >= 3 && numbers.size() == values.size()) {
        bool sequence = true;
        bool shortEnough = true;
        for (size_t index = 1; index < values.size(); ++index) {
            if (std::abs(numbers[index] - numbers[index - 1] - 1.0) > kSumEpsilon) {
                sequence = false;
                break;
            }
        }
        for (const auto& value : values) {
            if (value.size() > 3) shortEnough = false;
        }
        if (sequence && shortEnough) return ColumnRole::Sequence;
    }
    return ColumnRole::Subject;
}

bool looksLikeTotal(const TableRows& rows, int column, const std::vector<int>& subjects) {
    if (subjects.empty()) return false;
    int compared = 0;
    int matched = 0;
    for (const auto& row : rows) {
        double total = 0;
        if (column >= static_cast<int>(row.size()) || !parseNumber(trim(row[static_cast<size_t>(column)]), total)) {
            continue;
        }
        double sum = 0;
        bool complete = true;
        for (int index : subjects) {
            double part = 0;
            if (index >= static_cast<int>(row.size()) ||
                !parseNumber(trim(row[static_cast<size_t>(index)]), part)) {
                complete = false;
                break;
            }
            sum += part;
        }
        if (!complete) continue;
        ++compared;
        if (std::abs(sum - total) <= kSumEpsilon) ++matched;
    }
    return compared >= 3 && static_cast<double>(matched) / compared >= 0.75;
}

}  // namespace

TableHeader detectHeader(const TableRows& rows) {
    TableHeader header;
    if (rows.empty()) return header;

    size_t width = 0;
    for (const auto& row : rows) width = std::max(width, row.size());
    if (width == 0) return header;

    const std::vector<std::string>& first = rows.front();
    bool headerRow = false;
    int nonEmpty = 0;
    for (const auto& raw : first) {
        if (!trim(raw).empty()) ++nonEmpty;
    }
    if (nonEmpty >= 2) {
        const double firstNumeric = numericRatioOfRow(first);
        if (firstNumeric < 0.34) {
            bool hinted = false;
            for (const auto& raw : first) {
                if (isHeaderWord(trim(raw))) {
                    hinted = true;
                    break;
                }
            }
            headerRow = hinted || (rows.size() >= 2 && numericRatioOfRow(rows[1]) > firstNumeric);
        }
    }

    TableRows data;
    if (headerRow) {
        data.assign(rows.begin() + 1, rows.end());
    } else {
        data = rows;
    }

    header.fromImage = headerRow;
    header.columns.reserve(width);
    for (size_t column = 0; column < width; ++column) {
        ColumnSpec spec;
        spec.index = static_cast<int>(column);
        if (headerRow && column < first.size()) spec.name = trim(first[column]);
        ColumnRole role = headerRow ? roleFromName(spec.name) : ColumnRole::Other;
        if (role == ColumnRole::Other && !(headerRow && !spec.name.empty() && isHeaderWord(spec.name))) {
            const ColumnRole inferred = inferRole(data, static_cast<int>(column));
            if (role == ColumnRole::Other || inferred == ColumnRole::Id ||
                inferred == ColumnRole::Sequence || inferred == ColumnRole::Subject) {
                role = inferred;
            }
        }
        spec.role = role;
        header.columns.push_back(std::move(spec));
    }

    // 没有表头时也尽量把总分认出来：最后一列基本等于前面各科之和就是总分
    if (!headerRow && width >= 3) {
        const int last = static_cast<int>(width) - 1;
        std::vector<int> subjects;
        for (const auto& column : header.columns) {
            if (column.role == ColumnRole::Subject && column.index != last) subjects.push_back(column.index);
        }
        if (header.columns[static_cast<size_t>(last)].role == ColumnRole::Subject &&
            looksLikeTotal(data, last, subjects)) {
            header.columns[static_cast<size_t>(last)].role = ColumnRole::Total;
        }
    }
    return header;
}

CheckResult selfCheck(const TableRows& input, const TableConfidence* confidences,
                      const TableHeader* header) {
    CheckResult result;
    result.rows = input;
    if (input.empty() || input.front().empty()) return result;

    TableRows& rows = result.rows;
    const int columns = static_cast<int>(rows.front().size());

    std::vector<int> filled(static_cast<size_t>(columns), 0);
    std::vector<bool> numeric(static_cast<size_t>(columns), false);
    std::vector<bool> constant(static_cast<size_t>(columns), false);
    std::vector<bool> sequences(static_cast<size_t>(columns), false);
    std::vector<bool> identifiers(static_cast<size_t>(columns), false);

    for (int column = 0; column < columns; ++column) {
        int count = 0;
        for (int row = 0; row < static_cast<int>(rows.size()); ++row) {
            if (!trim(cellAt(rows, row, column)).empty()) ++count;
        }
        filled[static_cast<size_t>(column)] = count;
        numeric[static_cast<size_t>(column)] = isNumericColumn(rows, column);
        constant[static_cast<size_t>(column)] = isConstantColumn(rows, column);
        sequences[static_cast<size_t>(column)] = isSequenceColumn(rows, column);
        identifiers[static_cast<size_t>(column)] = isIdentifierColumn(rows, column);
    }

    std::set<int> suspicious;

    for (int column = 0; column < columns; ++column) {
        if (!sequences[static_cast<size_t>(column)]) continue;
        const std::vector<int> breaks = sequenceBreaks(rows, column);
        if (!breaks.empty()) {
            suspicious.insert(breaks.begin(), breaks.end());
            result.report.notes.push_back({Note::Kind::SequenceBreak, column,
                                           static_cast<int>(breaks.size())});
        } else if (filled[static_cast<size_t>(column)] > 1) {
            result.report.notes.push_back({Note::Kind::SequenceOk, column, 0});
        }
    }
    for (int column = 0; column < columns; ++column) {
        if (!identifiers[static_cast<size_t>(column)]) continue;
        const std::vector<int> breaks = sequenceBreaks(rows, column);
        if (!breaks.empty()) {
            suspicious.insert(breaks.begin(), breaks.end());
            result.report.notes.push_back({Note::Kind::IdBreak, column, static_cast<int>(breaks.size())});
        }
    }

    ColumnPair pair = resolveExplicit(header);
    // 表头认错列角色时宁可退回启发式，也不要按错的角色重算总分
    if (header != nullptr && !headerLooksPlausible(rows, *header, pair)) {
        pair.total = -1;
        pair.subjects.clear();
    }
    // 表头明确给了总分列和科目列时，总分就按各科相加算出来，不采信 OCR 的结果
    const bool derivedTotal = pair.total >= 0 && !pair.subjects.empty();
    if (pair.total < 0) {
        pair = findTotalColumn(rows, columns, numeric, constant, sequences, identifiers);
    }

    int corrected = 0;
    int partialTotals = 0;
    if (pair.total >= 0 && !pair.subjects.empty()) {
        const int totalColumn = pair.total;
        const std::vector<int>& subjects = pair.subjects;

        auto sumSubjects = [&subjects](const std::vector<std::string>& row, size_t& taken) {
            double sum = 0;
            taken = 0;
            for (int column : subjects) {
                double value = 0;
                if (column < static_cast<int>(row.size()) && parseNumber(trim(row[static_cast<size_t>(column)]), value)) {
                    ++taken;
                    sum += value;
                }
            }
            return sum;
        };

        for (int index = 0; index < static_cast<int>(rows.size()); ++index) {
            auto& row = rows[static_cast<size_t>(index)];
            size_t taken = 0;
            double sum = sumSubjects(row, taken);
            const std::string raw = trim(
                totalColumn < static_cast<int>(row.size()) ? row[static_cast<size_t>(totalColumn)] : "");
            double value = 0;
            const bool hasValue = parseNumber(raw, value);
            const bool allPresent = taken == subjects.size();

            if (derivedTotal) {
                if (static_cast<int>(taken) < kMinSubjectsForSum) {
                    suspicious.insert(index);
                    continue;
                }
                // 先拿 OCR 的总分试一次「把某一科改对」，再按改完的科目重算
                if (hasValue && allPresent && std::abs(sum - value) > kSumEpsilon) {
                    const auto fixed = fixSingleCell(row, index, subjects, totalColumn, sum, value, confidences);
                    if (fixed.has_value()) {
                        setCell(row, fixed->column, fixed->value);
                        ++corrected;
                        sum = sumSubjects(row, taken);
                    }
                }
                // 某一科读成了 712，和就会飙到几千分；这种明显不靠谱的和不写进去，
                // 保留卷面上那个总分，把行标成存疑更诚实。
                if (hasValue && value > 0 && sum > value * kSumOutlierRatio) {
                    suspicious.insert(index);
                    continue;
                }
                if (!hasValue || std::abs(value - sum) > kSumEpsilon) ++corrected;
                setCell(row, totalColumn, formatNumber(sum));
                if (!allPresent) {
                    ++partialTotals;
                    suspicious.insert(index);
                }
                continue;
            }

            if (!hasValue) {
                if (!raw.empty()) continue;
                if (static_cast<int>(taken) >= kMinSubjectsForSum) {
                    setCell(row, totalColumn, formatNumber(sum));
                    ++corrected;
                    if (!allPresent) ++partialTotals;
                } else {
                    suspicious.insert(index);
                }
                continue;
            }
            if (std::abs(sum - value) <= kSumEpsilon) continue;

            if (!allPresent) {
                suspicious.insert(index);
                continue;
            }

            const auto fixed = fixSingleCell(row, index, subjects, totalColumn, sum, value, confidences);
            if (fixed.has_value()) {
                setCell(row, fixed->column, fixed->value);
                ++corrected;
                continue;
            }
            const float totalConfidence = confidenceAt(confidences, index, totalColumn);
            if (totalConfidence < kRecomputeTotalConfidence) {
                setCell(row, totalColumn, formatNumber(sum));
                ++corrected;
            } else {
                suspicious.insert(index);
            }
        }
        result.report.notes.push_back(
            {Note::Kind::SumChecked, static_cast<int>(subjects.size()), totalColumn});
        if (partialTotals > 0) {
            result.report.notes.push_back({Note::Kind::PartialTotals, partialTotals, 0});
        }
    }

    for (int column = 0; column < columns; ++column) {
        if (!numeric[static_cast<size_t>(column)]) continue;
        for (int index = 0; index < static_cast<int>(rows.size()); ++index) {
            const std::string value = trim(cellAt(rows, index, column));
            if (value.empty()) continue;
            double ignored = 0;
            if (!parseNumber(value, ignored)) suspicious.insert(index);
        }
    }

    result.report.correctedCells = corrected;
    result.report.suspiciousRows.assign(suspicious.begin(), suspicious.end());
    return result;
}

}  // namespace ocr
