import Foundation

/// 一条识别记录；和 Android 的 entries 表对应。
struct TableEntry: Identifiable, Hashable {
    let id: Int
    let title: String
    let date: Date
    let rows: [[String]]
}

/// 临时占位数据，等 OCR 接上后由识别结果填充。
enum SampleData {
    static let entries: [TableEntry] = [
        TableEntry(
            id: 1,
            title: "高二(3)班 期中",
            date: Date().addingTimeInterval(-3600),
            rows: [
                ["序号", "姓名", "语文", "数学", "总分"],
                ["1", "示例甲", "92", "88", "180"],
                ["2", "示例乙", "85", "91", "176"],
            ]
        ),
        TableEntry(
            id: 2,
            title: "高二(3)班 月考",
            date: Date().addingTimeInterval(-86400),
            rows: [
                ["序号", "姓名", "语文", "数学", "总分"],
                ["1", "示例甲", "90", "93", "183"],
            ]
        ),
    ]
}
