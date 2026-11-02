#include "table/TableLogic.hpp"

#include <algorithm>
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
        for (size_t position = 0; position < raw.size(); ++position) {
            for (char digit = '0'; digit <= '9'; ++digit) {
                if (digit == raw[position]) continue;
                std::string candidate = raw;
                candidate[position] = digit;
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
    if (pair.total < 0) {
        pair = findTotalColumn(rows, columns, numeric, constant, sequences, identifiers);
    }

    int corrected = 0;
    int partialTotals = 0;
    if (pair.total >= 0 && !pair.subjects.empty()) {
        const int totalColumn = pair.total;
        const std::vector<int>& subjects = pair.subjects;
        for (int index = 0; index < static_cast<int>(rows.size()); ++index) {
            auto& row = rows[static_cast<size_t>(index)];
            std::vector<int> taken;
            double sum = 0;
            for (int column : subjects) {
                double value = 0;
                if (index < static_cast<int>(row.size()) && column < static_cast<int>(row.size()) &&
                    parseNumber(trim(row[static_cast<size_t>(column)]), value)) {
                    taken.push_back(column);
                    sum += value;
                }
            }
            const bool allPresent = taken.size() == subjects.size();
            const std::string raw = trim(
                totalColumn < static_cast<int>(row.size()) ? row[static_cast<size_t>(totalColumn)] : "");
            double value = 0;
            const bool hasValue = parseNumber(raw, value);

            if (!hasValue) {
                if (!raw.empty()) continue;
                if (static_cast<int>(taken.size()) >= kMinSubjectsForSum) {
                    row[static_cast<size_t>(totalColumn)] = formatNumber(sum);
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
                row[static_cast<size_t>(fixed->column)] = fixed->value;
                ++corrected;
                continue;
            }
            const float totalConfidence = confidenceAt(confidences, index, totalColumn);
            if (allPresent && totalConfidence < kRecomputeTotalConfidence) {
                row[static_cast<size_t>(totalColumn)] = formatNumber(sum);
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
