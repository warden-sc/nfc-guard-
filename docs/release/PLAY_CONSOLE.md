# Play Console 앱 콘텐츠 선언 답변안 (초안)

[출시 체크리스트](../RELEASE_CHECKLIST.md)로 돌아가기. 코드가 바뀌면 이 답변도 함께 고칩니다.
답변 근거가 되는 코드 위치를 함께 적어 둡니다.

## 1. 데이터 보안 (Data safety)

Play 기준에서 "수집"은 **기기 밖으로 전송**하는 것을 뜻합니다. 기기 안에만 저장하는 위치 기록은 수집에 해당하지 않습니다.

| 질문 | 답변 | 근거 |
|---|---|---|
| 사용자 데이터를 수집하거나 공유하나요? | 수집함 / 공유 안 함 | `UploadQueue`, `BackendClient` |
| 전송 중 암호화 | 예 (HTTPS) | 운영 서버 HTTPS, `network_security_config.xml`(평문은 로컬 개발 주소만) |
| 데이터 삭제 요청 방법 제공 | 예 (앱 > 개인정보 > 내 데이터 삭제) | `DELETE /api/v1/devices/me` |
| 수집이 선택 사항인가 | 예 (데이터 제공 동의, 언제든 철회) | `PrivacySettings`, 최초 실행 동의 대화상자 |

수집 데이터 유형:

| Play 데이터 유형 | 해당 내용 | 목적 | 필수/선택 |
|---|---|---|---|
| 기기 또는 기타 ID | 서버가 발급한 익명 기기 ID | 앱 기능(데이터 연결), 사기 방지·보안 | 선택(동의 시) |
| 앱 활동 > 기타 사용자 생성 콘텐츠 또는 앱 상호작용 | "내가 태그함/안 함" 응답, 감지 시각·판정 점수 | 분석(판정 개선) | 선택 |
| 앱 정보 및 성능 > 기타 앱 성능 데이터 | 태그 직전 움직임 센서 값, 근접·밝기·화면 상태, 모델 버전 | 분석(판정 개선) | 선택 |

수집하지 않음: 위치(기기 내 저장만), 개인 정보(이름·이메일·전화), 금융 정보, 연락처, 사진, 웹 기록.
※ 크래시 수집 SDK를 추가하면 "비정상 종료 로그"·"진단"을 추가해야 합니다.
※ Play Integrity API로 Google에 전달되는 정보는 Google 정책 기준으로 함께 검토합니다.

## 2. 포그라운드 서비스 권한 선언

`MotionForegroundService` (`android:foregroundServiceType="specialUse|location"`)

**specialUse 사유 (영문 제출용 초안)**
```
NFC Guard detects NFC taps that the user did not intend (e.g. a reader touching the phone inside a bag).
To judge a tap, the app must already hold the motion-sensor readings from the 2.5 seconds BEFORE the tap,
because an NFC field event arrives without warning and the decision must use past movement
(was the phone taken out and moved toward the reader?). Android does not deliver continuous sensor
events to background apps, so a user-visible foreground service keeps a rolling in-memory buffer of
accelerometer/gyroscope data while protection is ON. The user can turn protection off at any time from
the main screen, which stops the service. No other foreground service type covers continuous
sensor buffering for a security alert.
```

**location 타입 사유**
```
Only when the user turns on "save location with detection records": when an unintended NFC tap is detected,
the app measures the current location once and stores it ON THE DEVICE ONLY so the user can see where
the unexpected tap happened. Location is never uploaded and is not tracked continuously.
The app uses only foreground ("while in use") location permission; it does not request background location.
```

**시연 영상 촬영 목록** (YouTube 비공개 링크로 제출)
1. 앱 첫 실행 → 데이터 제공 동의 화면 → 메인 화면의 "보호 중"과 상단 보호 중 알림
2. 보호 스위치를 끄면 알림과 서비스가 사라지는 장면
3. 폰을 가방에 넣고 리더기(또는 `tools/hce_select_test.py`)로 태그 → 의심 알림 + 진동 → "내가 안 함" 응답
4. 위치 기록 켜기: 앱 내 안내 → 권한 요청 → 감지 기록에 위치·정확도·측정 시각 표시

## 3. 위치 권한
- 백그라운드 위치(`ACCESS_BACKGROUND_LOCATION`)를 요청하지 않으므로 별도 위치 권한 선언 양식 대상이 아닙니다.
- 권한 요청 전에 앱 안에서 용도를 먼저 알립니다(`location_disclosure_body`). Play의 "눈에 띄는 공개" 요건에 맞게 유지합니다.

## 4. 기타 선언
| 항목 | 답변안 |
|---|---|
| 광고 | 없음 |
| 앱 액세스 권한(심사용 로그인) | 로그인 없음. 심사 안내에 "NFC 기기·리더기가 필요하며, 감지 시연은 영상 참고" 기재 |
| 콘텐츠 등급 설문 | 도구/유틸리티, 폭력·성적 내용 없음 → 전체이용가 예상 |
| 타깃 연령 | 18세 이상 권장 (아동 대상 아님) |
| 금융 기능 | 금융 기능 제공 안 함 (결제·송금·대출 없음, 결제를 막거나 취소하지 않음) |
| 건강 / 정부 / 뉴스 앱 | 해당 없음 |
| 민감 권한(SMS·통화 기록·접근성 등) | 사용 안 함 |
| 개인정보처리방침 URL | `https://[서버 도메인]/privacy.html` (`server-java/src/main/resources/static/privacy.html`) |
