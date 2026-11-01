# 개발 환경 설정 가이드

`android/`(Android Studio)와 `server-java/`(IntelliJ IDEA)는 **서로 독립된 프로젝트**다.
저장소 루트를 통째로 열지 말고, 각 IDE에서 해당 폴더를 열 것.

---

## 1. Android 앱 — Android Studio

### 요구 버전
| 항목 | 버전 | 설정 위치 |
|---|---|---|
| Android Studio | Ladybug(2024.2) 이상 권장 | AGP 8.6 지원 버전 |
| JDK | 17 이상 (Android Studio 내장 JBR 사용 권장) | Settings > Build Tools > Gradle > Gradle JDK |
| Gradle | 8.9 (Wrapper 고정) | `android/gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.6.1 | `android/build.gradle.kts` |
| Kotlin | 1.9.24 | `android/build.gradle.kts` |
| compileSdk / targetSdk / minSdk | 35 / 34 / 26 | `android/app/build.gradle.kts` |

SDK Manager에서 **Android SDK Platform 35**와 **Build-Tools 35.x**를 설치한다.
compileSdk 35는 Android 15의 `processPollingFrames()`(Observe Mode)를 컴파일하기 위해 필요하다.

### 임포트
1. Android Studio > **Open** > `nfc-guard-/android` 폴더 선택 (저장소 루트 아님)
2. Gradle Sync 자동 실행. `local.properties`(SDK 경로)는 Android Studio가 생성한다(커밋 금지, .gitignore 처리됨)
3. Sync 실패 시 확인할 것
   - `android.useAndroidX=true`가 `android/gradle.properties`에 있는지 (없으면 AndroidX 의존성 오류)
   - Gradle JDK가 17 이상인지
   - SDK Platform 35가 설치되어 있는지
4. 단위 테스트: `app/src/test` 우클릭 > Run Tests (`GestureMatcherTest`, `GestureModelTest` — 기기 불필요)

### 서버 주소 (`BuildConfig.BACKEND_URL`)
| 실행 환경 | 설정 |
|---|---|
| 에뮬레이터 → PC의 로컬 서버 | 기본값 `http://10.0.2.2:8080` 그대로 |
| 실기기(USB) → PC의 로컬 서버 | `adb reverse tcp:8080 tcp:8080` 실행 후 `~/.gradle/gradle.properties`에 `nfcguard.backendUrl=http://localhost:8080` |
| 실서버 | `nfcguard.backendUrl=https://...` (평문 HTTP는 10.0.2.2/localhost만 허용됨) |

### 에뮬레이터로 되는 것 / 안 되는 것
| 기능 | 에뮬레이터 | 이유 |
|---|---|---|
| 화면(UI), 권한 요청, 알림, 서버 연동 | 가능 | |
| NFC 필드 감지(HCE) | **불가** | 에뮬레이터에는 NFC 컨트롤러가 없음 |
| 보행 진동, 꺼내는 동작 등 실제 모션 | **사실상 불가** | Extended controls의 가상 센서는 회전만 흉내 내고, 사람 움직임의 가속도 패턴은 재현하지 못함 |
| 근접/조도 센서 | 수동 값만 가능 | Extended controls > Virtual sensors |
| 화면 꺼짐 중 센서 FIFO 동작 | 불가 | 하드웨어 센서 허브가 없음 |

→ **감지 로직 검증은 실기기 필수.**

### 실기기 테스트 환경
**준비물**
- NFC/HCE 지원 Android 폰 (Android 15 이상이면 Observe Mode 확인 가능)
- NFC 리더 역할을 할 장치 중 하나:
  - **USB NFC 리더기**(ACR122U 등) + `tools/hce_select_test.py` — 데모 AID로 SELECT를 보내 HCE를 깨움 (권장, 반복 가능)
  - 다른 Android 폰 + IsoDep으로 SELECT를 보내는 테스트 앱
  - 실제 교통/결제 단말 — 데모 AID를 SELECT하지 않으므로 `processCommandApdu`는 호출되지 않음.
    Android 15 Observe Mode 지원 기기에서 `processPollingFrames` 경로를 확인하는 용도

**절차**
1. 폰: 개발자 옵션 > USB 디버깅 켜기, NFC 켜기
2. Android Studio에서 실기기로 Run → 앱에서 알림 권한 허용, 보호 켜기
3. 로그 보기:
   ```bash
   adb logcat -s NfcGuardHce AnomalyReporter NfcGuardMain BackendClient ModelUpdater
   ```
   - `NfcGuardMain`의 `observeModeSupported=true/false`로 기기의 감지 수준 확인
4. 리더기 테스트:
   ```bash
   pip install pyscard
   python tools/hce_select_test.py --repeat 5 --interval 4
   ```
   - 폰을 손에 들고 꺼내서 대기 → 위험도 낮음
   - 폰을 가방/파우치에 넣은 채 리더기 위 → `tapped_while_in_bag_or_pocket`, 알림 + 진동
   - 화면 끈 상태에서도 반복 (센서 FIFO flush 경로 검증)
5. 서버 연동 확인: 아래 서버를 띄운 뒤 감지 → 알림의 "내가 태그함/내가 안 함" → 서버 export에 라벨 반영

### 핵심 모듈 위치
| 파일 | 역할 |
|---|---|
| `res/xml/apduservice.xml` | HCE 서비스가 응답할 데모 AID, (주석) Android 15 polling-loop-filter 자리 |
| `NfcGuardHceService.kt` | 필드 감지 진입점: `processCommandApdu` / `processPollingFrames` → 3초 디바운스 → 판정 |
| `MotionForegroundService.kt` | 센서 상시 수집용 Foreground Service |
| `MotionBuffer.kt` | 가속도/자이로 2.5초 링버퍼, 화면 꺼짐 중 FIFO flush |
| `DeviceContextMonitor.kt` | 근접/조도/화면 상태 (가방·주머니 판단) |
| `GestureMatcher.kt` | 보행 노치 필터 → 순변위/자세변화 → 가방 신호 결합 → 위험도 |
| `GestureModel.kt`, `TfliteGestureClassifier.kt`, `ModelUpdater.kt` | AI 모델 전처리/추론/검증·교체 |
| `AnomalyReporter.kt` | 알림·진동 판단, 서버 전송, 감지 기록 저장 |
| `BackendClient.kt` | 서버 API 호출 (기기 등록/이벤트/라벨/모델) |

---

## 2. 백엔드 서버 — IntelliJ IDEA

### 요구 버전
| 항목 | 버전 |
|---|---|
| JDK | 17 이상 (`pom.xml`의 `java.version`=17) |
| Spring Boot | 3.3.2 |
| Maven | Wrapper 포함(`./mvnw`, Maven 3.9.9) — 별도 설치 불필요 |
| IntelliJ IDEA | Community도 가능 (Ultimate는 Spring 전용 도구 추가 제공) |

### 임포트
1. IntelliJ > **Open** > `nfc-guard-/server-java/pom.xml` 선택 > **Open as Project**
2. File > Project Structure > Project SDK = 17 이상
3. Maven 동기화 후 `NfcGuardServerApplication` 의 실행 버튼(▶)으로 시작
4. 실행 구성(Run > Edit Configurations)에 환경변수 추가:
   - `NFCGUARD_ADMIN_API_KEY=<임의의 긴 값>` — 학습 데이터 export용 (없으면 export는 항상 401)
   - PostgreSQL 사용 시 `SPRING_PROFILES_ACTIVE=postgres` (아래 참고)

명령줄 실행:
```bash
cd server-java
NFCGUARD_ADMIN_API_KEY=dev-admin ./mvnw spring-boot:run
./mvnw test
```

### 동작 확인 (curl)
```bash
# 1) 기기 등록 -> deviceToken, apiKey
curl -s -X POST localhost:8080/api/v1/devices/register

# 2) 이벤트 수집
curl -s -X POST localhost:8080/api/v1/events \
  -H "X-Device-Token: <deviceToken>" -H "Authorization: Bearer <apiKey>" \
  -H "Content-Type: application/json" \
  -d '{"riskScore":0.9,"reason":"tapped_while_in_bag_or_pocket","repeatCount":1,
       "occurredAtEpochMs":1700000000000,
       "rawSensorWindow":[{"t":0,"ax":0.1,"ay":0,"az":0,"gx":0,"gy":0,"gz":0}]}'
# -> {"id":1}

# 3) 라벨 확정 (본인 기기 이벤트만)
curl -s -X POST "localhost:8080/api/v1/events/1/confirm?wasIntentional=false" \
  -H "X-Device-Token: <deviceToken>" -H "Authorization: Bearer <apiKey>"

# 4) 학습용 JSONL export
curl -s "localhost:8080/api/v1/events/dataset/export?afterId=0" -H "X-Admin-Key: dev-admin"
```

### 데이터베이스
**기본: H2 파일 DB** (설정 없음)
- `application.properties`: `jdbc:h2:file:./data/nfcguard` → 실행 위치 기준 `data/nfcguard.mv.db` 생성 (.gitignore 처리됨)
- 스키마는 `ddl-auto=update`로 엔티티에서 자동 생성

**PostgreSQL 전환** (`postgres` 프로필)
1. DB 띄우기
   ```bash
   cd server-java
   docker compose up -d        # postgres:16, db/user/password = nfcguard
   ```
2. 프로필 켜고 실행
   ```bash
   SPRING_PROFILES_ACTIVE=postgres ./mvnw spring-boot:run
   ```
3. 다른 DB 서버면 환경변수로 지정: `NFCGUARD_DB_URL`, `NFCGUARD_DB_USER`, `NFCGUARD_DB_PASSWORD`
4. 실제 PostgreSQL 대상 통합 테스트:
   ```bash
   NFCGUARD_TEST_PG_URL=jdbc:postgresql://localhost:5432/nfcguard ./mvnw test
   ```

**PostgreSQL 전환 시 주의**
- 센서 시계열 컬럼은 `@Lob`을 쓰면 PostgreSQL에서 `oid`(Large Object)로 매핑되어
  조회 시 `Unable to access lob stream` 오류가 난다. 그래서 `@JdbcTypeCode(SqlTypes.LONG32VARCHAR)`로
  PostgreSQL `text` / H2 `varchar`에 매핑한다 (`AnomalyEvent.rawSensorWindowJson`).
- 실서비스에서는 `ddl-auto=update` 대신 Flyway 마이그레이션 + `NFCGUARD_DDL_AUTO=validate` 권장.
- 기존 H2 데이터는 자동으로 옮겨지지 않는다. 필요하면 export(JSONL)로 백업 후 재수집.

### 백엔드 코드 구조
```
server-java/src/main/java/com/nfcguard/server/
├── NfcGuardServerApplication.java
├── config/WebConfig.java                 인터셉터 등록 (경로별 인증 방식)
├── auth/
│   ├── DeviceAuthService.java            기기 등록, API 키 해시 저장/검증
│   ├── DeviceAuthInterceptor.java        X-Device-Token + Bearer 검증, 기기별 시간당 요청 제한
│   └── AdminKeyInterceptor.java          dataset export용 X-Admin-Key 검증
├── controller/
│   ├── DeviceController.java             POST /api/v1/devices/register
│   ├── AnomalyEventController.java       POST /events, POST /events/{id}/confirm, GET /events/dataset/export
│   └── ModelDistributionController.java  GET /model/metadata, /model/latest, /model/version
├── service/AnomalyEventService.java      수집 검증, 라벨 소유권 확인, JSONL 직렬화
├── repository/                           Spring Data JPA
└── model/
    ├── AnomalyEvent.java                 이벤트 엔티티 (anomaly_events)
    ├── AnomalyEventRequest.java          수집 요청 DTO
    └── Device.java                       익명 기기 엔티티 (devices)
```
