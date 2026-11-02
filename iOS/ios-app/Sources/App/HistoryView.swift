import SwiftUI

/// 历史页：一条记录一行，点开压栈到详情。
struct HistoryView: View {
    let onOpen: (TableEntry) -> Void
    private let entries = SampleData.entries

    var body: some View {
        List(entries) { entry in
            Button {
                onOpen(entry)
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(entry.title).font(.body)
                        Text(entry.date, format: .dateTime.year().month().day().hour().minute())
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text("\(entry.rows.count - 1) 行")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .buttonStyle(.plain)
        }
        .navigationTitle("历史")
        .overlay {
            if entries.isEmpty {
                ContentUnavailableView("还没有记录", systemImage: "clock")
            }
        }
    }
}

#Preview {
    NavigationStack { HistoryView(onOpen: { _ in }) }
}
