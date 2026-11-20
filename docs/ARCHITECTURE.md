# NFC Guard 현재 구현 아키텍처

> **문서 역할:** 현재 개발 브랜치의 코드 흐름을 설명합니다. 2026-11-11 회의에서 확정한 목표는 [PROJECT_SCOPE.md](PROJECT_SCOPE.md)를 우선합니다.
> 현재 코드는 **비차단형 탐지·알림·기록** 경로입니다. 회의에서 정한 **NFC 동작 제한, 제한·해제 UX, 위치 로그**는 추가 구현·실기기 검증 대상입니다.
> 아래 위험도는 내부 판정 점수이며 검증된 실제 공격 확률이 아닙니다. 관찰 가능한 이벤트와 제한 가능한 NFC 경로는 별도로 확인합니다.

## 현재 규칙이 가정하는 상황

아래 표는 규칙 설계 시의 기대 동작입니다. 모든 실사용 환경에서 검증된 판정 결과를 뜻하지 않습니다.
| 상황 | 기기 움직임 | 근접센서 | 조도 | 기대 판정 |
|---|---|---|---|---|
| 사용자가 폰을 꺼내 리더기에 댐 | 태그 직전 큰 이동 + 회전 | 열림 | 밝음 | 정상 (위험도 낮음) |
| 가방/주머니 속에서 태그됨 | 없음 또는 보행 진동뿐 | 가려짐 | 어두움 | **즉시 알림 + 진동** |
| 정지 상태 폰에 리더기를 갖다 댐 | 없음 | 열림 | 밝음 | 알림 (동작 없음) |
| 손가락으로 센서를 가린 채 직접 태그 | 큰 이동 | 가려짐 | 어두움 | 애매함 (반복 시에만 알림) |

화면 꺼짐은 약한 신호로만 사용한다. 교통카드는 화면이 꺼진 채 정상적으로 쓰는 경우가 흔하기 때문이다.

## 판정 흐름 (Android)
```
MotionForegroundService ── 상시 ──> MotionBuffer(가속도/자이로 2.5s)   DeviceContextMonitor(근접/조도/화면)
                                          │                                   │
NFC 필드 감지 ─────────────────────────────┤                                   │
 (processCommandApdu / processPollingFrames)                                  │
   │  3초 디바운스, 응답은 즉시 반환 (트랜잭션 비차단)                          │
   ▼                                                                          │
 주변상태 스냅샷 ◄──────────────────────────────────────────────────────────────┘
 MotionBuffer.flushThen()  ── 센서 허브 FIFO의 최근 값 수신 (최대 150ms)
   ▼
 GestureMatcher.evaluate()      보행 노치 필터 → 순변위/자세변화 → 동작 위험도
                                + 가방/주머니 점수 (noisy-OR)
   ▼
 RiskBlender.blend()            TFLite 모델 p(의도적) → 임계값 보정 위험도와 혼합 (모델 있을 때만)
   ▼
 위험도 > 0.4 → AnomalyReporter
   ├ ≥ 0.7 또는 1분 내 3회 → 알림 + HapticAlert 진동 (네트워크 대기 없음)
   ├ 서버 업로드 → 이벤트 id
   └ 알림에 "내가 태그함 / 내가 안 함" 버튼 → LabelActionReceiver → confirm
 위험도 ≤ 0.4 → 5% 확률로 정상 샘플 업로드 (라벨 미확정)
```

### 왜 FIFO flush인가
화면 꺼짐, 가방 수납, CPU 절전(suspend)은 서로 같은 상태가 아니다. 화면이 꺼진 뒤 CPU가 절전 상태에 들어가면
non-wakeup 센서의 이벤트 전달이 지연될 수 있다. 지원 하드웨어에서는 센서 FIFO에 버퍼링된 값을
`SensorManager.flush()`로 받아 판정하도록 구현되어 있다. FIFO 용량·버퍼링·CPU 깨움은 기종별 확인이 필요하다.
현재 코드는 FIFO가 2.5초 창보다 작은 기종에서 `PARTIAL_WAKE_LOCK`을 사용하는 보완 경로를 갖는다(배터리 실측 필요).

### 필드 감지 경로의 한계
- `processCommandApdu`: 리더기가 **이 앱의 AID**를 SELECT할 때만 호출된다. 실제 스키머는 결제 AID를 보내므로
  이 경로는 데모/검증용이다. 결제 AID를 직접 등록하면 실결제가 깨지므로 금지.
- `processPollingFrames` (Android 15+, API 35): 리더기 폴링(필드 ON)을 결제 응답 이전에 관찰한다.
  다른 서비스로 가는 폴링까지 서드파티 앱이 받는지는 기종/NFC 컨트롤러에 따라 다르므로 실기기 검증이 필요하다.
  `apduservice.xml`의 `polling-loop-filter`(autoTransact=false)는 실제 리더기 폴링 패턴을 수집한 뒤 활성화한다.

## 데이터 계약
### 앱 → 서버 이벤트 (`POST /api/v1/events`)
```json
{
  "source": "field_detection | onboarding",
  "riskScore": 0.95, "reason": "tapped_while_in_bag_or_pocket", "repeatCount": 1,
  "occurredAtEpochMs": 1700000000000,
  "netDisplacement": 0.001, "orientationShift": 0.1, "pocketScore": 1.0,
  "proximityNear": true, "ambientLux": 1.5, "screenInteractive": false,
  "modelVersion": 1792849250872,
  "rawSensorWindow": [{"t": 0, "ax": 0.1, "ay": 0, "az": 0, "gx": 0, "gy": 0, "gz": 0}]
}
```
- 현재 서버는 `onboarding` 이벤트를 의도적 태깅 라벨로 편입한다. 이는 수집 절차에 대한 구현상 가정이며 실제 공격 여부의 검증이 아니다. 통제 실험과 사용자 응답의 출처를 구분하는 개선은 별도 반영 대상이다.
- `deviceToken`은 본문이 아니라 인증 헤더에서 가져온다(사칭 방지).

### 학습 데이터 (`GET /api/v1/events/dataset/export`, JSONL)
줄마다 `id, source, risk_score, reason, ..., confirmed_intentional, raw_sensor_window`.
`ml-python/data_loader.py`가 `raw_sensor_window`를 96포인트·6채널(`ax..gz`)로 인덱스 기준 선형보간한다.

## 모델 학습·배포
```
UCI HAR (body_acc g→m/s², body_gyro) ─ pretrain ─> har_backbone.keras (입력 정규화 포함)
서버 JSONL ─ finetune(백본 고정, 헤드만 학습, 층화 분할) ─> 검증셋 ROC로 임계값 선택
   ─> model_export: gesture_classifier.tflite + .json(버전/sha256/입력스펙/임계값/지표) + .json.sig(ECDSA P-256)
   ─> ../server-java/models (tmp → os.replace 원자적 교체)
앱 ModelUpdater(하루 1회): metadata 서명 검증 → 버전 비교 → tflite 다운로드 → sha256 검증 → 교체
```
- **전처리 일치가 가장 중요하다.** 학습(`data_loader._resample_window`)과 추론(`SensorPreprocessor.resample`)이
  다르면 오류 없이 성능만 조용히 무너진다. 양쪽 테스트(`ml-python/tests`, `android/app/src/test`)가 같은 픽스처로 이를 고정한다.
- 서명: 모델이 변조되면 "항상 정상"을 내는 모델로 탐지를 꺼버릴 수 있으므로, 앱은 공개키(`MODEL_SIGNING_PUBLIC_KEY`)로
  메타데이터 서명을 검증하고, 메타데이터 안의 sha256으로 모델 파일을 검증한다. 공개키가 비어 있으면 개발 모드(sha256만).
- 모델 검증·로딩 실패 시 기존 모델 또는 휴리스틱으로 판단하는 대체 경로가 있다. 이 경로가 NFC 이벤트 미수신·센서 중단까지 해결한다는 뜻은 아니다.

## 확정 범위와 현재 코드의 차이

| 확정 목표 | 현재 코드 | 추가 반영 |
|---|---|---|
| AI + 규칙 | 두 결과를 혼합하는 경로가 있음 | 실측 데이터·백본·전처리 확인 및 평가 |
| NFC 동작 제한 | 응답을 즉시 반환하는 비차단형 서비스 | 제어 API·권한·실기기 제한/복구 검증 및 구현 |
| 불편을 줄이는 UX | 메인·온보딩·알림 응답 화면이 있음 | 제한 상태 안내·해제·재시도 흐름 |
| 시각·위치 로그 | 시각·이유·점수·응답 요약이 있음 | 위치 권한·수집·정확도·측정 시각·표시 |

기존 데이터 계약에는 위치 필드가 없다. 필드 초안과 정확도 결정 방식은 [위치 로그 기준](PROJECT_SCOPE.md#5-위치가-포함된-로그)을 본다.
