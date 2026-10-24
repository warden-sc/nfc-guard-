# NFC Guard 아키텍처

## 무엇을 구분하려는가
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
가방 안 = 화면 꺼짐 = CPU 절전(suspend) 상태다. 이때 non-wakeup 센서 이벤트는 앱으로 오지 않고
센서 허브 FIFO에 쌓인다(가득 차면 오래된 것부터 덮어쓰므로 항상 "최근 값"이 남는다).
NFC 필드가 CPU를 깨우면 `SensorManager.flush()`로 그 값을 받아 판정한다.
FIFO가 2.5초 창보다 작은 기종만 `PARTIAL_WAKE_LOCK`으로 보완한다(배터리 비용).

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
- `onboarding` 이벤트는 서버에서 바로 정상(의도적) 라벨로 편입된다. 의심 이벤트만 쌓이면 클래스가 한쪽으로 치우치기 때문이다.
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
- 어떤 검증이든 실패하면 기존 모델 또는 휴리스틱 단독으로 계속 동작한다. 탐지가 꺼지는 실패 경로는 없다.
