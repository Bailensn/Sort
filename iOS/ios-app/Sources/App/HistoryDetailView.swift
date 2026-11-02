import SwiftUI

/// 历史详情：和识别页同一套表格结构，顶部三个视图切换（原图 / 网格 / 增强），右下导出。
struct HistoryDetailView: View {
    let entry: TableEntry
    @State private var mode = 0

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Picker("视图", selection: $mode) {
                    Text("原图").tag(0)
                    Text("网格").tag(1)
                    Text("增强").tag(2)
                }
                .pickerStyle(.segmented)

                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.secondarySystemBackground))
                    .aspectRatio(4.0 / 3.0, contentMode: .fit)
                    .overlay {
                        Text(["原图", "网格", "增强"][mode])
                            .foregroundStyle(.secondary)
                    }

                TableGridView(rows: entry.rows)
            }
            .padding()
        }
        .navigationTitle(entry.title)
        .navigationBarTitleDisplayMode(.inline)
        .overlay(alignment: .bottomTrailing) {
            Button {
                // 导出接上后调 XlsxExporter
            } label: {
                Image(systemName: "square.and.arrow.up")
                    .font(.system(size: 22, weight: .medium))
                    .frame(width: 60, height: 60)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .padding(20)
        }
    }
}

#Preview {
    NavigationStack {
        HistoryDetailView(entry: SampleData.entries[0])
    }
}
