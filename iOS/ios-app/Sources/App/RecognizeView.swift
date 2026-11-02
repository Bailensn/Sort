import PhotosUI
import SwiftUI

/// 识别页，卡片顺序和 Android 一致：预览 → 统计 → 表头 → 表格。
struct RecognizeView: View {
    @State private var picked: PhotosPickerItem?
    @State private var image: UIImage?
    @State private var rows: [[String]] = []
    @State private var status = String(localized: "recognize.empty")
    @State private var running = false
    @State private var elapsed: Double = 0
    @State private var blanks = 0
    @State private var header: [String] = []

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                preview
                if running { ProgressView() } else {
                    Text(status).font(.footnote).foregroundStyle(.secondary)
                }
                if !rows.isEmpty {
                    section("stats.title")
                    StatsCard(rows: rows, elapsed: elapsed, blanks: blanks)
                    section("header.title")
                    HeaderRow(names: $header)
                    section("preview.title")
                    TableGridView(rows: rows)
                }
            }
            .frame(maxWidth: 720).frame(maxWidth: .infinity)
            .padding(12)
        }
        .navigationTitle(Text("recognize.title"))
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                PhotosPicker(selection: $picked, matching: .images) { Text("recognize.pick") }
            }
        }
        .task(id: picked) {
            guard let picked, let data = try? await picked.loadTransferable(type: Data.self),
                  let ui = UIImage(data: data) else { return }
            image = ui
            await recognize(ui)
        }
    }

    private var preview: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground))
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                Text("recognize.noImage").foregroundStyle(.secondary)
            }
        }
        .aspectRatio(4.0 / 3.0, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func section(_ key: LocalizedStringKey) -> some View {
        HStack { Text(key).font(.footnote).foregroundStyle(.secondary); Spacer() }
    }

    private func recognize(_ ui: UIImage) async {
        guard let cg = ui.cgImage else { return }
        running = true
        status = String(localized: "recognize.running")
        let started = Date()
        let lines = await Task.detached(priority: .userInitiated) {
            OcrBridge.bundled()?.recognize(cg) ?? []
        }.value
        elapsed = Date().timeIntervalSince(started)
        let built = TableBuilder.build(lines)
        rows = built.rows
        header = built.header
        blanks = built.blanks
        running = false
        status = rows.isEmpty
            ? String(localized: "recognize.noText")
            : String(localized: "recognize.done")
    }
}

/// OCR 文本框 → 行：按纵向中心聚成行，行内按 x 排序。
enum TableBuilder {
    struct Built { var rows: [[String]] = []; var header: [String] = []; var blanks = 0 }

    static func build(_ lines: [OcrLine]) -> Built {
        guard !lines.isEmpty else { return Built() }
        var clusters: [[OcrLine]] = []
        for line in lines.sorted(by: { $0.top < $1.top }) {
            let center = (line.top + line.bottom) / 2
            if let first = clusters.last?.first {
                let lastCenter = (first.top + first.bottom) / 2
                let tolerance = max(first.bottom - first.top, line.bottom - line.top) * 0.6
                if abs(center - lastCenter) <= tolerance { clusters[clusters.count - 1].append(line); continue }
            }
            clusters.append([line])
        }
        var rows = clusters.map { $0.sorted { $0.left < $1.left }.map(\.text) }
        let width = rows.map(\.count).max() ?? 0
        rows = rows.map { $0 + Array(repeating: "", count: width - $0.count) }
        var header: [String] = []
        if let first = rows.first, first.allSatisfy({ $0.rangeOfCharacter(from: .decimalDigits) == nil }) {
            header = first
        }
        let blanks = rows.reduce(0) { $0 + $1.filter(\.isEmpty).count }
        return Built(rows: rows, header: header, blanks: blanks)
    }
}

struct StatsCard: View {
    let rows: [[String]]
    let elapsed: Double
    let blanks: Int

    var body: some View {
        VStack(spacing: 0) {
            line(Text("stats.size"), Text(verbatim: "\(rows.count) × \(rows.map(\.count).max() ?? 0)"))
            Divider()
            line(Text("stats.elapsed"), Text(verbatim: String(format: "%.1f s", elapsed)))
            Divider()
            line(Text("stats.blanks"), Text(verbatim: "\(blanks)"))
        }
        .padding(.horizontal, 12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
    }

    private func line(_ label: Text, _ value: Text) -> some View {
        HStack { label.font(.footnote); Spacer(); value.font(.footnote.monospacedDigit()) }
            .padding(.vertical, 10)
    }
}

/// 表头按表格排：一行可编辑的列名，和下面的表格同宽。
struct HeaderRow: View {
    @Binding var names: [String]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(names.indices, id: \.self) { index in
                    TextField("header.column", text: Binding(
                        get: { index < names.count ? names[index] : "" },
                        set: { if index < names.count { names[index] = $0 } }
                    ))
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 104)
                }
            }
            .padding(.vertical, 4)
        }
    }
}

/// 与 Android 的表格同一套结构：0.5pt 发丝线，行高 40。
struct TableGridView: View {
    let rows: [[String]]

    private var columns: Int { rows.map(\.count).max() ?? 0 }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            VStack(spacing: 0) {
                ForEach(rows.indices, id: \.self) { r in
                    HStack(spacing: 0) {
                        ForEach(0..<columns, id: \.self) { c in
                            Text(cell(r, c))
                                .font(.caption).lineLimit(1).minimumScaleFactor(0.7)
                                .frame(width: 104, minHeight: 40, alignment: .leading)
                                .padding(.horizontal, 6)
                                .background(r == 0 ? Color(.secondarySystemBackground) : Color(.systemBackground))
                                .overlay(Rectangle().stroke(Color(.separator), lineWidth: 0.5))
                        }
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }

    private func cell(_ row: Int, _ column: Int) -> String {
        guard row < rows.count, column < rows[row].count else { return "" }
        return rows[row][column]
    }
}

#Preview { NavigationStack { RecognizeView() } }
