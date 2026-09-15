import SwiftUI

struct SafetyTip: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
}

struct SafetyTipsView: View {
    let tips: [SafetyTip] = [
        SafetyTip(title: "카드 사용 알림 켜두기", detail: "소액 결제까지 즉시 알림이 오도록 설정하면 무단 거래를 가장 빨리 알아챌 수 있습니다."),
        SafetyTip(title: "출처 불명 앱 설치 금지", detail: "실제 국내 NFC 정보탈취 사건 다수가 스미싱으로 악성 앱을 설치시킨 뒤 저장된 카드정보를 빼가는 방식이었습니다."),
        SafetyTip(title: "혼잡한 곳에서 가방 위치 신경쓰기", detail: "지하철 등 혼잡 구간에서는 가방을 몸 앞쪽으로 메는 것만으로도 근접 스캔 시도를 물리적으로 어렵게 만듭니다."),
        SafetyTip(title: "RFID 차단 파우치, 성능 검증된 제품 사용", detail: "차단 성능이 부실한 저가 제품이 시중에 많으니 검증된 제품인지 확인하세요."),
        SafetyTip(title: "정기적인 보안 업데이트", detail: "OS와 결제 앱을 최신 상태로 유지하세요.")
    ]

    var body: some View {
        NavigationView {
            List(tips) { tip in
                VStack(alignment: .leading, spacing: 4) {
                    Text(tip.title).font(.headline)
                    Text(tip.detail).font(.subheadline).foregroundColor(.secondary)
                }
                .padding(.vertical, 4)
            }
            .navigationTitle("예방 정보")
        }
    }
}
