import SwiftUI

/*
 iOS 스코프 한계 (반드시 인지):
 - Apple Wallet / Secure Element 트랜잭션은 서드파티 앱에 어떤 이벤트도 노출되지 않음
 - Core NFC reader session은 사용자가 명시적으로 시작해야 하고 백그라운드 상시 리스닝 불가
 - 따라서 이 앱은 "자동 탐지·차단"이 아니라
   1) 사용자가 직접 카드/여권 NFC 태그를 점검하는 도구
   2) 유사시 스스로 기록하는 셀프 리포트 로그
   3) 예방 교육 콘텐츠
   로 스코프를 명확히 좁힌다.
*/

@main
struct NfcGuardLiteApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
