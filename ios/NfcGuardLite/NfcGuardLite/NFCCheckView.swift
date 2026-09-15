import SwiftUI
import CoreNFC

/// 사용자가 명시적으로 버튼을 눌러야만 세션이 열림 (iOS 정책상 백그라운드 상시 감지 불가).
/// 용도: "내 카드/태그가 의도치 않게 다른 정보를 노출하고 있지는 않은지" 직접 점검.
final class NFCCheckModel: NSObject, ObservableObject, NFCTagReaderSessionDelegate {
    @Published var lastResult: String = "아직 스캔한 기록이 없습니다."
    private var session: NFCTagReaderSession?

    func startScan() {
        guard NFCTagReaderSession.readingAvailable else {
            lastResult = "이 기기는 NFC 태그 읽기를 지원하지 않습니다."
            return
        }
        session = NFCTagReaderSession(pollingOption: [.iso14443], delegate: self)
        session?.alertMessage = "카드/태그를 아이폰 상단에 가까이 대주세요"
        session?.begin()
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        DispatchQueue.main.async {
            self.lastResult = "스캔 종료: \(error.localizedDescription)"
        }
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard let tag = tags.first else { return }
        session.connect(to: tag) { error in
            if let error = error {
                DispatchQueue.main.async { self.lastResult = "연결 실패: \(error.localizedDescription)" }
                return
            }
            // 여기서는 태그 UID/타입 정도만 사용자에게 보여줌 (카드 잔액/거래내역 등 민감정보는 다루지 않음)
            var summary = "태그 감지됨.\n"
            switch tag {
            case .iso7816(let t):
                summary += "타입: ISO7816 (결제/교통카드 계열)\nUID: \(t.identifier.map { String(format: "%02X", $0) }.joined())"
            default:
                summary += "타입: 기타 NFC 태그"
            }
            DispatchQueue.main.async {
                self.lastResult = summary
                session.invalidate()
            }
        }
    }
}

struct NFCCheckView: View {
    @StateObject private var model = NFCCheckModel()

    var body: some View {
        NavigationView {
            VStack(spacing: 20) {
                Text("내 카드/태그를 직접 스캔해서 확인합니다.\n(자동 감지는 iOS 정책상 불가능 — 매번 버튼을 눌러야 합니다)")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding()

                Button("지금 스캔하기") {
                    model.startScan()
                }
                .buttonStyle(.borderedProminent)

                Text(model.lastResult)
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(12)

                Spacer()
            }
            .padding()
            .navigationTitle("카드 점검")
        }
    }
}
