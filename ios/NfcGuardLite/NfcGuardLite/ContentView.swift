import SwiftUI

struct ContentView: View {
    var body: some View {
        TabView {
            NFCCheckView()
                .tabItem { Label("카드 점검", systemImage: "wave.3.right.circle") }

            SelfReportLogView()
                .tabItem { Label("셀프 리포트", systemImage: "clock.badge.exclamationmark") }

            SafetyTipsView()
                .tabItem { Label("예방 정보", systemImage: "shield.lefthalf.filled") }
        }
    }
}

#Preview {
    ContentView()
}
