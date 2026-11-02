import SwiftUI

/// 与 Android 一致的页面骨架：两个页签（识别 / 历史），历史详情压栈打开。
struct RootView: View {
    @State private var selection = 0
    @State private var detail: TableEntry?

    var body: some View {
        TabView(selection: $selection) {
            NavigationStack {
                RecognizeView()
            }
            .tabItem { Label("识别", systemImage: "doc.text.viewfinder") }
            .tag(0)

            NavigationStack {
                HistoryView(onOpen: { detail = $0 })
                    .navigationDestination(item: $detail) { entry in
                        HistoryDetailView(entry: entry)
                    }
            }
            .tabItem { Label("历史", systemImage: "clock") }
            .tag(1)
        }
    }
}

#Preview {
    RootView()
}
