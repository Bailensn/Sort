#pragma once

#include <optional>
#include <string>
#include <vector>

namespace ocr {

/// 表格的行数据：外层是行，内层是单元格文本。
using TableRows = std::vector<std::vector<std::string>>;

/// 每格置信度，和 rows 一一对应；拿不到就传 nullptr。
using TableConfidence = std::vector<std::vector<float>>;

enum class ColumnRole {
    Sequence,
    Id,
    Subject,
    Total,
    Other,
};

struct ColumnSpec {
    int index = 0;
    std::string name;
    ColumnRole role = ColumnRole::Other;
};

/// 表格结构：每列叫什么、是什么身份。fromImage 表示第一行确实是表头。
struct TableHeader {
    std::vector<ColumnSpec> columns;
    bool fromImage = false;

    std::vector<int> subjectColumns() const;
    std::optional<int> totalColumn() const;
    bool hasNames() const;
};

/// 认表头：认不出就返回空名字的结构，交给用户填。
TableHeader detectHeader(const TableRows& rows);

struct Note {
    enum class Kind {
        SequenceBreak,  // a = 列, b = 断了几处
        SequenceOk,     // a = 列
        IdBreak,        // a = 列, b = 断了几处
        SumChecked,     // a = 科目数, b = 总分的列
        PartialTotals,  // a = 行数
    };

    Kind kind = Kind::SequenceOk;
    int a = 0;
    int b = 0;
};

struct Report {
    std::vector<Note> notes;
    int correctedCells = 0;
    std::vector<int> suspiciousRows;
};

struct CheckResult {
    TableRows rows;
    Report report;
};

/// 算术自检：序号/学号连续性、科目求和与总分对账、单格反推。
/// header 非空且标了总分列与科目列时按它算，否则按「最右一列是总分」猜。
CheckResult selfCheck(const TableRows& input, const TableConfidence* confidences = nullptr,
                      const TableHeader* header = nullptr);

/// 分数列里 5→S、0→O、全角数字这类错字按列纠正：整列大多是数字才动手。
TableRows repairNumbers(const TableRows& rows);

/// 供 C ABI 与测试共用：严格匹配 ^\d+(\.\d+)?$。
bool parseNumber(const std::string& raw, double& out);

}  // namespace ocr
