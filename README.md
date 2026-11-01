# NFC Guard — 의도하지 않은 NFC 태그 감지

**목표**: 사용자가 기기를 꺼내 리더기에 대는 의도적 동작이 없었는데 NFC가 태그되면 알린다.
대표 시나리오: 가방·주머니 속 기기에 몰래 리더기를 대는 스키밍.
트랜잭션은 절대 막지 않고 **사후 알림 + 진동**으로 대응한다.

설계 상세(판정 흐름, 데이터 계약, 모델 배포)는 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md),
Android Studio / IntelliJ 설정과 실기기 테스트, PostgreSQL 전환은 [`docs/SETUP.md`](docs/SETUP.md) 참고.

## 구조
- `android/` — 본체. 필드 감지 + 동작/주변상태 기반 판정 + 사후 알림·진동 + 온디바이스 AI 모델.
- `server-java/` — 이벤트·라벨 수집, 학습 데이터 export, 모델 배포 (Spring Boot).
- `ml-python/` — HAR 백본 사전학습 → NFC Guard 라벨로 파인튜닝 → 서명된 TFLite 배포.
- `ios/NfcGuardLite/` — 축소판 (앱 아이콘 미적용). 자동 탐지 불가(플랫폼 한계)로 수동 점검/셀프 리포트/교육 콘텐츠로 스코프 조정.

## Android 핵심 흐름
1. `MotionForegroundService` — 가속도계/자이로/근접/조도를 상시 수집 (Android 9+ 백그라운드 센서 차단 대응, 재부팅 시 `BootReceiver`가 자동 시작)
2. `MotionBuffer` — 최근 2.5초 링버퍼. 화면 꺼짐(=가방 안) 중엔 센서 허브 FIFO에 쌓인 값을 `flushThen()`으로 끌어와 판정
3. `NfcGuardHceService` — 필드 감지(`processCommandApdu`, Android 15+는 `processPollingFrames`) → 3초 디바운스 → 판정. 응답은 즉시 반환
4. `GestureMatcher` — 보행 대역(1.3~2.6Hz) 노치 필터 → 잔차 이중적분 순변위 + 자세변화 + **가방/주머니 신호(`DeviceContextMonitor`)** → 위험도
5. `RiskBlender` — 학습된 TFLite 모델이 있으면 모델 점수를 섞음 (없으면 휴리스틱 단독)
6. `AnomalyReporter` — 고위험(≥0.7) 단건은 즉시, 애매한 건은 1분 내 3회 이상일 때 **알림 + 전용 진동 패턴(`HapticAlert`)**
7. 알림의 **"내가 태그함 / 내가 안 함"** 버튼 → 서버 라벨 확정 → 파인튜닝 정답 데이터
8. `OnboardingActivity` — 사용자별 태깅 제스처 baseline 학습 + 정상 샘플로 서버 업로드

## 실행 방법
```bash
# 서버
cd server-java
NFCGUARD_ADMIN_API_KEY=<임의의 긴 값> mvn spring-boot:run
mvn test   # API 통합 테스트

# 모델 서명 키 (최초 1회, 개인키는 커밋 금지 - .gitignore 처리됨)
cd ml-python
pip install -r requirements.txt
python model_export.py --generate-key ./keys
#   출력된 공개키를 android/app/build.gradle.kts 의 MODEL_SIGNING_PUBLIC_KEY 에 넣기

# 백본 사전학습 (UCI HAR 데이터를 직접 받아서)
python pretrain_backbone_from_scratch.py --uci-root "./data/UCI HAR Dataset"

# 파인튜닝 -> ../server-java/models 에 서명된 모델 기록 -> 앱이 하루 1회 받아감
NFCGUARD_ADMIN_API_KEY=<서버와 같은 값> python finetune.py --signing-key ./keys/model_signing_private.pem
python -m pytest tests   # TensorFlow 없이 도는 파이프라인 테스트

# 앱: Android Studio에서 android/ 열기. GestureMatcher/GestureModel 단위 테스트는 app/src/test
#   BuildConfig.BACKEND_URL 기본값 http://10.0.2.2:8080 (에뮬레이터 -> 로컬 서버)
```

## 지금 당장 반드시 확인/검증해야 할 것 (아직 안 끝난 부분)
- **필드 감지 범위 (가장 중요)**: `processCommandApdu`는 리더기가 **우리 데모 AID를 SELECT할 때만** 호출됨.
  실제 스키머는 결제/교통 AID를 보내므로 이 경로로는 공격을 거의 못 봄.
  Android 15+ `processPollingFrames`(Observe Mode) 경로를 추가했지만, 서드파티 앱이 다른 서비스로 가는
  폴링까지 받는지는 기종/NFC 컨트롤러별로 다름 → **실기기 검증 필수** (`MainActivity` 로그의 `observeModeSupported` 확인).
  결제 AID를 이 서비스가 직접 소유하면 안 됨 — 실결제가 깨짐.
- **임계값 실측**: 노치 필터, 가방/주머니 가중치(`DARK_LUX`, `POCKET_WEIGHT`), 위험도 임계값(0.4/0.7)은 합성 신호로만 검증됨.
  실제 지하철/보행 데이터로 ROC를 뽑아 재조정 필요.
- **드리프트 문제**: 이중적분 기반 변위 추정은 근본적으로 부정확함 (물리 법칙상 한계). 짧은 창(1.5초)으로 완화한 상태.
- **배터리**: 하드웨어 FIFO가 작은 기종은 wake lock을 잡음. 기종별 실측 필요. 제조사 배터리 최적화(강제 종료) 예외 안내도 필요할 수 있음.
- **오프라인 유실**: 서버 전송 실패 시 재전송 큐가 아직 없음 (`AnomalyReporter` TODO). 알림/진동은 네트워크와 무관하게 동작.
- **기기 등록 남용**: `/api/v1/devices/register`는 누구나 호출 가능 → Play Integrity 검증 추가 권장.
- **카드사 연동**: 실제 카드사/발급사 이상거래탐지(FDS) 연동 계약이 있어야 "세션 무효화"가 실질적 효과를 가짐
  (개인 개발자가 임의로 카드사 세션을 무효화할 권한은 없음 — 사업화 시 가장 큰 장벽).

## iOS가 자동 탐지를 할 수 없는 이유 (재확인)
- Apple Wallet/Secure Element 트랜잭션 이벤트는 서드파티 앱에 비공개
- Core NFC reader session은 사용자 명시적 시작 필요, 백그라운드 상시 리스닝 불가
- 따라서 iOS 버전은 "탐지·차단"이 아니라 "사용자 스스로 점검·기록·학습" 도구로 스코프 고정

## 서버(Java) API
| 메서드 | 경로 | 인증 | 용도 |
|---|---|---|---|
| POST | `/api/v1/devices/register` | 없음 | 익명 기기 토큰 + API 키 발급 |
| POST | `/api/v1/events` | 기기 | 의심 이벤트 / 온보딩·정상 샘플 업로드 (원본 센서 시계열 포함), 응답 `{"id"}` |
| POST | `/api/v1/events/{id}/confirm?wasIntentional=` | 기기(본인 이벤트만) | 알림 버튼 → 라벨 확정 |
| GET | `/api/v1/events/dataset/export?afterId=` | `X-Admin-Key` | 라벨링 데이터 JSONL (증분) |
| GET | `/api/v1/model/metadata` | 없음 | 모델 메타데이터 원문 + `X-Model-Signature` |
| GET | `/api/v1/model/latest` | 없음 | TFLite 모델 |

- 기기 인증 헤더: `X-Device-Token`, `Authorization: Bearer <apiKey>`. 기기별 시간당 요청 제한 있음.
- 기본 DB는 H2 파일 DB (실배포 시 PostgreSQL 등으로 교체 권장). Docker/배포 설정은 아직 없음.

## ml-python
- **중요**: "NFC 태깅 의도" 전용 사전학습 모델은 세상에 없음. UCI HAR 같은 범용 행동인식 데이터로
  백본을 만들고 NFC Guard 라벨로 분류 헤드만 재학습하는 전이학습 구조.
  UCI HAR는 50Hz·2.56초 창, body_acc/body_gyro가 안드로이드 선형가속도/자이로와 같은 물리량이라 잘 맞음.
- **데이터 자체가 아직 없음**: 실사용자 온보딩 + 알림 라벨이 누적돼야 `finetune.py`가 의미 있게 돌아감
  (50건 미만이면 경고 출력). 합성 데이터로는 사전학습 → 파인튜닝 → TFLite → 서명 → 앱 검증까지 배관만 확인됨.

## 앱스토어/플레이스토어 제출 전 남은 작업
- Android: Android Studio에서 프로젝트 임포트 → 빌드 확인 → 실기기 NFC 테스트
  (에뮬레이터는 NFC 하드웨어 시뮬레이션이 제한적이라 실기기 필수)
- Android: 앱 아이콘(적응형/테마 아이콘/알림 아이콘)과 메인·온보딩 화면은 구현됨.
  플레이스토어 등록용 512px 아이콘은 `android/app/src/main/ic_launcher-playstore.png`
- Android: Foreground Service `specialUse` 타입은 Play 심사 시 사용 사유 설명 필요
- iOS: Mac + Xcode에서 프로젝트 열어서 서명/프로비저닝 프로파일 설정 → 실기기 빌드
  (Core NFC는 시뮬레이터에서 동작 안 함, 실기기 필수)
- 두 플랫폼 다 개인정보처리방침 페이지 필요 (센서/NFC 데이터 수집 고지)
