import SwiftUI

/// 识别页：顶栏（标题 + 换一张）、4:3 预览、进度、结果表格、右下导出。
struct RecognizeView: View {
    @State private var pickedImage: UIImage?
    @State private var progress: Double = 0
    @State private var rows: [[String]] = []
    @State private var status: String = "选一张成绩单"

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                preview

                Text(status)
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                if progress > 0 && progress < 1 {
                    ProgressView(value: progress)
                }

                if !rows.isEmpty {
                    TableGridView(rows: rows)
                }
            }
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
            .padding()
        }
        .navigationTitle("识别")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("换一张") { pick() }
            }
        }
        .overlay(alignment: .bottomTrailing) {
            Button {
                // 导出：接上后调 XlsxExporter
            } label: {
                Image(systemName: "square.and.arrow.up")
                    .font(.system(size: 22, weight: .medium))
                    .frame(width: 60, height: 60)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .padding(20)
            .disabled(rows.isEmpty)
        }
    }

    private var preview: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.secondarySystemBackground))
            if let pickedImage {
                Image(uiImage: pickedImage)
                    .resizable()
                    .scaledToFit()
            } else {
                Text("还没有图片")
                    .foregroundStyle(.secondary)
            }
        }
        .aspectRatio(4.0 / 3.0, contentMode: .fit)
    }

    private func pick() {
        // PhotoPicker 接上后替换
        status = "选图功能待接入"
    }
}

/// 与 Android 的表格同一套结构：首行表头，0.5pt 发丝线，行高 40。
struct TableGridView: View {
    let rows: [[String]]

    private var columns: Int { rows.map(\.count).max() ?? 0 }

    var body: some View {
        VStack(spacing: 0) {
            ForEach(rows.indices, id: \.self) { r in
                HStack(spacing: 0) {
                    ForEach(0..<columns, id: \.self) { c in
                        Text(cell(r, c))
                            .font(r == 0 ? .caption.bold() : .caption)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                            .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
                            .padding(.horizontal, 6)
                    }
                }
                .overlay(alignment: .bottom) {
                    Rectangle().fill(Color(.separator)).frame(height: 0.5)
                }
            }
        }
        .overlay(
            RoundedRectangle(cornerRadius: 8).stroke(Color(.separator), lineWidth: 0.5)
        )
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func cell(_ row: Int, _ column: Int) -> String {
        guard row < rows.count, column < rows[row].count else { return "" }
        return rows[row][column]
    }
}

#Preview {
    NavigationStack { RecognizeView() }
}
