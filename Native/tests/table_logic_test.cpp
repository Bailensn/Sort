// 表格后处理的共享实现测试：断言和安卓侧 TableSelfCheckTest / TableHeaderTest 一一对应，
// 迁移时两边跑同一套用例，行为不会漂。
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "table/ColumnCleaner.hpp"
#include "table/GridBuilder.hpp"
#include "table/TableLogic.hpp"

namespace {

int g_failures = 0;

void check(bool condition, const char* message) {
    if (condition) return;
    std::printf("FAIL: %s\n", message);
    ++g_failures;
}

ocr::TableRows cleanTable() {
    return {
        {"14", "1", "900000000001", "示例甲", "94.5", "7", "88", "25", "59", "56", "329.5"},
        {"14", "2", "900000000002", "示例乙", "77.5", "24", "77", "30", "70", "56", "334.5"},
        {"14", "3", "900000000003", "示例丙", "95", "54", "60.5", "47", "72", "83", "411.5"},
        {"14", "4", "900000000004", "示例丁", "87.5", "68", "52.5", "42", "62", "76", "388"},
        {"14", "5", "900000000005", "示例戊", "81.5", "44", "69.5", "48", "75", "78", "396"},
        {"14", "6", "900000000006", "示例己", "69", "59", "50", "23", "45", "55", "301"},
    };
}

ocr::TableConfidence confidences(const ocr::TableRows& rows) {
    ocr::TableConfidence out;
    for (const auto& row : rows) out.emplace_back(row.size(), 0.99f);
    return out;
}

void keepsCleanTableUntouched() {
    const ocr::TableRows rows = cleanTable();
    const ocr::TableConfidence conf = confidences(rows);
    const ocr::CheckResult result = ocr::selfCheck(rows, &conf, nullptr);

    check(result.report.correctedCells == 0, "clean: 不该纠正任何格子");
    check(result.report.suspiciousRows.empty(), "clean: 不该有存疑行");
    check(result.rows == rows, "clean: 行数据应原样返回");
}

void fixesSumMismatchWithSingleDigitEdit() {
    ocr::TableRows rows = cleanTable();
    rows[4][5] = "41";
    ocr::TableConfidence conf = confidences(rows);
    conf[4][5] = 0.55f;

    const ocr::CheckResult result = ocr::selfCheck(rows, &conf, nullptr);

    check(result.report.correctedCells == 1, "sum-fix: 应该正好纠正一格");
    check(result.rows[4][5] == "44", "sum-fix: 低置信度的 41 应改回 44");
    check(result.rows[4][10] == "396", "sum-fix: 总分不该被动");
}

void leavesAmbiguousMismatchAlone() {
    ocr::TableRows rows = cleanTable();
    rows[4][5] = "41";
    rows[4][6] = "60.5";
    const ocr::TableConfidence conf = confidences(rows);

    const ocr::CheckResult result = ocr::selfCheck(rows, &conf, nullptr);

    check(result.report.correctedCells == 0, "ambiguous: 不该硬改");
    check(result.rows[4][5] == "41", "ambiguous: 原值应保留");
    check(!result.report.suspiciousRows.empty() && result.report.suspiciousRows[0] == 4,
          "ambiguous: 第 5 行应进存疑");
}

void detectsSequenceGap() {
    ocr::TableRows rows = cleanTable();
    rows[3][1] = "5";
    rows[4][1] = "6";
    rows[5][1] = "7";
    const ocr::TableConfidence conf = confidences(rows);

    const ocr::CheckResult result = ocr::selfCheck(rows, &conf, nullptr);

    bool flagged = false;
    for (int row : result.report.suspiciousRows) {
        if (row == 3) flagged = true;
    }
    check(flagged, "sequence: 断档行应进存疑");

    bool note = false;
    for (const auto& item : result.report.notes) {
        if (item.kind == ocr::Note::Kind::SequenceBreak) note = true;
    }
    check(note, "sequence: 应给出 SequenceBreak");
}

void detectsColumnThatIsStillNotNumeric() {
    ocr::TableRows rows = cleanTable();
    rows[2][4] = "95分";
    const ocr::TableConfidence conf = confidences(rows);

    const ocr::CheckResult result = ocr::selfCheck(rows, &conf, nullptr);

    bool flagged = false;
    for (int row : result.report.suspiciousRows) {
        if (row == 2) flagged = true;
    }
    check(flagged, "non-numeric: 第 3 行应进存疑");
}

void flagsNonSequentialIds() {
    ocr::TableRows rows = cleanTable();
    rows[3][2] = "900000000075";
    const ocr::TableConfidence conf = confidences(rows);

    const ocr::CheckResult result = ocr::selfCheck(rows, &conf, nullptr);

    bool flagged = false;
    for (int row : result.report.suspiciousRows) {
        if (row == 3) flagged = true;
    }
    check(flagged, "id: 中断的学号行应进存疑");

    bool note = false;
    for (const auto& item : result.report.notes) {
        if (item.kind == ocr::Note::Kind::IdBreak) note = true;
    }
    check(note, "id: 应给出 IdBreak");
}

void detectsHeaderAndRoles() {
    const ocr::TableRows rows = {
        {"姓名", "学号", "语文", "数学", "总分"},
        {"张三", "900000000001", "90", "85", "175"},
        {"李四", "900000000002", "80", "70", "150"},
        {"王五", "900000000003", "70", "60", "130"},
    };

    const ocr::TableHeader header = ocr::detectHeader(rows);

    check(header.fromImage, "header: 应认出表头");
    check(header.columns.size() == 5, "header: 列数应为 5");
    check(header.columns[1].role == ocr::ColumnRole::Id, "header: 学号列应为 Id");
    check(header.totalColumn().value_or(-1) == 4, "header: 总分列应为 4");
    const auto subjects = header.subjectColumns();
    check(subjects.size() == 2 && subjects[0] == 2 && subjects[1] == 3, "header: 科目列应为 2、3");
}

void ignoresHeaderWhenFirstRowIsData() {
    const ocr::TableRows rows = {
        {"张三", "900000000001", "90", "85", "175"},
        {"李四", "900000000002", "80", "70", "150"},
        {"王五", "900000000003", "70", "60", "130"},
    };

    const ocr::TableHeader header = ocr::detectHeader(rows);

    check(!header.fromImage, "no-header: 第一行是数据，不该认成表头");
    check(!header.hasNames(), "no-header: 不该有列名");
    check(header.columns[1].role == ocr::ColumnRole::Id, "no-header: 学号列仍应识别");
    // 最后一列等于前面之和，没有表头也要认成总分
    check(header.totalColumn().value_or(-1) == 4, "no-header: 总分列应为 4");
    const auto subjects = header.subjectColumns();
    check(subjects.size() == 2 && subjects[0] == 2 && subjects[1] == 3, "no-header: 科目列应为 2、3");
}

void usesExplicitTotalColumnInTheMiddle() {
    const ocr::TableRows rows = {
        {"张三", "90", "175", "85"},
        {"李四", "80", "150", "70"},
        {"王五", "70", "130", "60"},
    };
    ocr::TableHeader header;
    header.columns = {
        {0, "姓名", ocr::ColumnRole::Other},
        {1, "语文", ocr::ColumnRole::Subject},
        {2, "总分", ocr::ColumnRole::Total},
        {3, "数学", ocr::ColumnRole::Subject},
    };

    const ocr::CheckResult result = ocr::selfCheck(rows, nullptr, &header);

    bool checked = false;
    for (const auto& item : result.report.notes) {
        if (item.kind == ocr::Note::Kind::SumChecked) {
            checked = true;
            check(item.a == 2, "explicit: 科目数应为 2");
            check(item.b == 2, "explicit: 总分列应为 2");
        }
    }
    check(checked, "explicit: 应给出 SumChecked");
    check(result.rows[0][2] == "175", "explicit: 表里的总分应保留");
}

void fixesWrongTotalWhenExplicit() {
    ocr::TableRows rows = {
        {"张三", "90", "170", "85"},
        {"李四", "80", "150", "70"},
        {"王五", "70", "130", "60"},
    };
    ocr::TableHeader header;
    header.columns = {
        {0, "姓名", ocr::ColumnRole::Other},
        {1, "语文", ocr::ColumnRole::Subject},
        {2, "总分", ocr::ColumnRole::Total},
        {3, "数学", ocr::ColumnRole::Subject},
    };

    const ocr::CheckResult result = ocr::selfCheck(rows, nullptr, &header);

    const bool fixed = result.rows[0][2] == "175";
    bool flagged = false;
    for (int row : result.report.suspiciousRows) {
        if (row == 0) flagged = true;
    }
    check(fixed || flagged, "explicit: 总分算错的行要么改对、要么进存疑");
}

void derivesTotalFromSubjects() {
    ocr::TableRows rows = {
        {"张三", "90", "150", "85"},
        {"李四", "80", "150", "70"},
        {"王五", "70", "130", "60"},
    };
    ocr::TableHeader header;
    header.columns = {
        {0, "姓名", ocr::ColumnRole::Other},
        {1, "语文", ocr::ColumnRole::Subject},
        {2, "总分", ocr::ColumnRole::Total},
        {3, "数学", ocr::ColumnRole::Subject},
    };
    const ocr::TableConfidence conf = confidences(rows);

    const ocr::CheckResult result = ocr::selfCheck(rows, &conf, &header);

    // 表里写的 150 置信度再高也不采信：总分就是各科之和
    check(result.rows[0][2] == "175", "derived: 第一行总分应为 175");
    check(result.rows[2][2] == "130", "derived: 第三行本来就对，不该动");
}

/// 某一科读飞了（712 而不是 71.2），和会变成几千分；这种和不能写进总分。
void keepsPrintedTotalWhenSumExplodes() {
    ocr::TableRows rows = {
        {"张三", "712", "170", "85"},
        {"李四", "80", "150", "70"},
    };
    ocr::TableHeader header;
    header.columns = {
        {0, "姓名", ocr::ColumnRole::Other},
        {1, "语文", ocr::ColumnRole::Subject},
        {2, "总分", ocr::ColumnRole::Total},
        {3, "数学", ocr::ColumnRole::Subject},
    };
    const ocr::TableConfidence conf = confidences(rows);

    const ocr::CheckResult result = ocr::selfCheck(rows, &conf, &header);

    check(result.rows[0][2] == "170", "outlier: 和炸了就该保留卷面总分");
    bool flagged = false;
    for (int row : result.report.suspiciousRows) {
        if (row == 0) flagged = true;
    }
    check(flagged, "outlier: 这一行要进存疑");
}

/// 小数点被吃掉：945 应该是 94.5，靠卷面总分反推回来。
void restoresDroppedDecimalPoint() {
    ocr::TableRows rows = {
        {"张三", "945", "179.5", "85"},
        {"李四", "80", "150", "70"},
    };
    ocr::TableHeader header;
    header.columns = {
        {0, "姓名", ocr::ColumnRole::Other},
        {1, "语文", ocr::ColumnRole::Subject},
        {2, "总分", ocr::ColumnRole::Total},
        {3, "数学", ocr::ColumnRole::Subject},
    };
    const ocr::TableConfidence conf = confidences(rows);

    const ocr::CheckResult result = ocr::selfCheck(rows, &conf, &header);

    check(result.rows[0][1] == "94.5", "decimal: 945 应被补成 94.5");
    check(result.rows[0][2] == "179.5", "decimal: 总分应保持 179.5");
}

void repairsLookalikeDigits() {
    ocr::TableRows rows = {
        {"张三", "95"},
        {"李四", "S8"},
        {"王五", "70"},
        {"示例庚", "85"},
    };

    const ocr::TableRows fixed = ocr::repairNumbers(rows);

    check(fixed[1][1] == "58", "repair: S8 应修成 58");
    check(fixed[0][1] == "95", "repair: 正常的数字不该被动");
}

void repairsFullWidthDigits() {
    ocr::TableRows rows = {
        {"张三", "95"},
        {"李四", "８８"},
        {"王五", "70"},
        {"示例庚", "85"},
    };

    const ocr::TableRows fixed = ocr::repairNumbers(rows);

    check(fixed[1][1] == "88", "repair: 全角 88 应修成 88");
}

void repairsIdentifierLookalikes() {
    ocr::TableRows rows = {
        {"1", "900000000001", "示例甲"},
        {"2", "9O0000OOO0O2", "示例乙"},
        {"3", "900000000003", "示例丙"},
        {"4", "900000000004", "示例丁"},
    };

    const ocr::TableRows fixed = ocr::repairIdentifiers(rows);

    check(fixed[1][1] == "900000000002", "repair: 学号里的 O 应补回 0");
    check(fixed[0][1] == "900000000001", "repair: 正常学号不该被动");
    check(fixed[1][2] == "示例乙", "repair: 姓名列不该被动");
}

void leavesTextColumnAlone() {
    ocr::TableRows rows = {
        {"张三", "优秀"},
        {"李四", "良好"},
        {"王五", "及格"},
    };

    const ocr::TableRows fixed = ocr::repairNumbers(rows);

    check(fixed[0][1] == "优秀", "repair: 文字列不该被改");
}

void trimsGarbageEdges() {
    const ocr::TableRows rows = {
        {"", "1", "张三", "95", "Ab"},
        {"", "2", "李四", "88", "Cd"},
        {"", "3", "王五", "70", "Ef"},
    };

    const ocr::TrimmedColumns trimmed = ocr::trimColumns(rows);

    check(trimmed.droppedLeading == 1, "trim: 左边空列应被丢掉");
    check(trimmed.rows.front().size() == 3, "trim: 右边字母列应被丢掉");
    check(trimmed.rows[0][1] == "张三", "trim: 剩下的列应对齐");
}

void cleansNoisyColumn() {
    const ocr::TableRows rows = {
        {"1", "张三", "95", "x"},
        {"2", "李四", "", "y"},
        {"3", "王五", "70", ""},
        {"4", "示例庚", "", "z"},
    };

    const ocr::TableRows cleaned = ocr::cleanNoisyColumns(rows);

    check(cleaned[0].size() == 4, "noisy: 列数不该变，只清空");
    check(cleaned[0][2] == "95", "noisy: 正常列不该被动");
}

void cleansNonNumericNonChineseColumn() {
    const ocr::TableRows rows = {
        {"1", "张三", "95", "Ab12"},
        {"2", "李四", "88", "Ab34"},
        {"3", "王五", "70", "Ab56"},
        {"4", "示例庚", "66", "Ab78"},
    };

    const ocr::TableRows cleaned = ocr::cleanBadColumns(rows);

    check(cleaned[0][3].empty(), "bad: 非数字非中文的列应被清空");
    check(cleaned[0][1] == "张三", "bad: 姓名列必须保留");
}

void buildsGridFromLines() {
    ocr::GridLines lines;
    lines.vertical = {0, 50, 100};
    lines.horizontal = {0, 50, 100};

    std::vector<ocr::TextRegion> regions = {
        {5, 5, 40, 30, "A"},
        {55, 5, 95, 30, "B"},
        {5, 55, 40, 80, "C"},
        {55, 55, 95, 80, "D"},
    };

    const auto grid = ocr::buildFromLines(regions, lines);

    check(grid.has_value(), "grid: 有线时应该切出表格");
    check(grid->rows.size() == 2 && grid->rows[0].size() == 2, "grid: 应为 2×2");
    check(grid->rows[0][1] == "B" && grid->rows[1][0] == "C", "grid: 格子内容应为 B、C");
}

void buildsGridFromClusters() {
    std::vector<ocr::TextRegion> regions = {
        {0, 0, 40, 20, "张三"},
        {60, 0, 100, 20, "95"},
        {0, 40, 40, 60, "李四"},
        {60, 40, 100, 60, "88"},
    };

    const auto grid = ocr::buildFromClusters(regions);

    check(grid.has_value(), "cluster: 无划线时也应该聚类出表格");
    check(grid->rows.size() == 2, "cluster: 应为 2 行");
    check(grid->rows[0][0] == "张三" && grid->rows[0][1] == "95", "cluster: 首行应为 张三/95");
}

void dropsTrailingEmptyColumn() {
    const ocr::TableRows cells = {
        {"1", "张三", ""},
        {"2", "李四", ""},
    };

    const auto grid = ocr::normalizeCells(cells, true);

    check(grid.has_value(), "normalize: 不该返回空");
    check(grid->rows[0].size() == 2, "normalize: 尾部空列应被裁掉");
}

}  // namespace

int main() {
    keepsCleanTableUntouched();
    fixesSumMismatchWithSingleDigitEdit();
    leavesAmbiguousMismatchAlone();
    detectsSequenceGap();
    detectsColumnThatIsStillNotNumeric();
    flagsNonSequentialIds();
    detectsHeaderAndRoles();
    ignoresHeaderWhenFirstRowIsData();
    usesExplicitTotalColumnInTheMiddle();
    fixesWrongTotalWhenExplicit();
    derivesTotalFromSubjects();
    keepsPrintedTotalWhenSumExplodes();
    restoresDroppedDecimalPoint();
    repairsLookalikeDigits();
    repairsFullWidthDigits();
    repairsIdentifierLookalikes();
    leavesTextColumnAlone();
    trimsGarbageEdges();
    cleansNoisyColumn();
    cleansNonNumericNonChineseColumn();
    buildsGridFromLines();
    buildsGridFromClusters();
    dropsTrailingEmptyColumn();

    if (g_failures > 0) {
        std::printf("%d 项失败\n", g_failures);
        return 1;
    }
    std::printf("table logic: 全部通过\n");
    return 0;
}
