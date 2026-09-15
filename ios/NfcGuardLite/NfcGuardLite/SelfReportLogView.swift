import SwiftUI

/*
 iOS는 Wallet 거래 발생 이벤트를 앱에 넘겨주지 않으므로 자동 로그는 불가능.
 대신 사용자가 "방금 애플페이/트랜짓 알림 왔는데 내가 한 거 맞나?" 판단한 결과를
 스스로 기록하게 해서, 나중에 패턴(예: 특정 시간대·장소에 몰려 있는 '아니오' 응답)을
 본인이 직접 돌아볼 수 있게 하는 용도.
*/

struct SelfReportEntry: Identifiable, Codable {
    let id: UUID
    let timestamp: Date
    let wasIntentional: Bool
    let memo: String
}

final class SelfReportStore: ObservableObject {
    @Published var entries: [SelfReportEntry] = []
    private let key = "nfcguard_selfreport_entries"

    init() { load() }

    func add(wasIntentional: Bool, memo: String) {
        entries.insert(SelfReportEntry(id: UUID(), timestamp: Date(), wasIntentional: wasIntentional, memo: memo), at: 0)
        save()
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: key),
              let decoded = try? JSONDecoder().decode([SelfReportEntry].self, from: data) else { return }
        entries = decoded
    }

    private func save() {
        if let data = try? JSONEncoder().encode(entries) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }
}

struct SelfReportLogView: View {
    @StateObject private var store = SelfReportStore()
    @State private var memo: String = ""

    var body: some View {
        NavigationView {
            VStack {
                Text("지갑 알림(Apple Pay/교통카드 태그 등)을 받았는데\n본인이 한 게 맞는지 애매할 때 기록해두세요.")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                TextField("메모 (선택, 예: 장소/상황)", text: $memo)
                    .textFieldStyle(.roundedBorder)
                    .padding(.horizontal)

                HStack {
                    Button("내가 한 거 맞음") {
                        store.add(wasIntentional: true, memo: memo)
                        memo = ""
                    }
                    .buttonStyle(.bordered)

                    Button("기억 없음 / 의심됨") {
                        store.add(wasIntentional: false, memo: memo)
                        memo = ""
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                }
                .padding()

                List(store.entries) { entry in
                    HStack {
                        Image(systemName: entry.wasIntentional ? "checkmark.circle" : "exclamationmark.triangle")
                            .foregroundColor(entry.wasIntentional ? .green : .red)
                        VStack(alignment: .leading) {
                            Text(entry.timestamp, style: .date) + Text(" ") + Text(entry.timestamp, style: .time)
                            if !entry.memo.isEmpty {
                                Text(entry.memo).font(.caption).foregroundColor(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle("셀프 리포트")
        }
    }
}
