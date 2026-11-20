# Google Play 출시 체크리스트

[프로젝트 첫 화면](../README.md) · [확정 범위](PROJECT_SCOPE.md) · [스토어 문구](release/STORE_LISTING.md) · [Play Console 선언](release/PLAY_CONSOLE.md)

표시: ✅ 코드·문서 반영됨 · 👤 운영 담당자가 직접 해야 함 · 🔬 실기기 검증 필요 · ⏳ 미착수

출시 범위는 [확정 범위](PROJECT_SCOPE.md)를 따릅니다. **NFC 동작 제한은 실기기 검증 전까지 출시 문구·화면에서 완성 기능으로 표시하지 않습니다.**

---

## 0단계: 핵심 동작 실기기 검증 (출시 여부를 결정)

| 항목 | 상태 | 방법 |
|---|---|---|
| 실제 단말(교통 개찰구·결제 단말)에서 앱이 NFC 이벤트를 받는지 | 🔬 | Android 15+ 픽셀·갤럭시에서 `adb logcat -s NfcGuardHce` → `field detected via polling_loop` 확인 ([SETUP](SETUP.md)) |
| 앱이 실제로 제한·복구할 수 있는 NFC 경로 | 🔬 | [확정 범위 2절](PROJECT_SCOPE.md#2-nfc-제한이-뜻하는-것). 일반 앱은 리더 모드(앱이 화면에 있을 때만), Observe Mode(지갑 역할 조건), NFC 설정 화면 이동 정도로 제한됨 |
| 위 결과에 따라 출시 범위 확정 | 👤 | 받을 수 있는 이벤트·제한 가능 범위를 스토어 문구와 앱 "감지 수준" 표시에 반영 |

## 1단계: 앱 완성

| 항목 | 상태 | 비고 |
|---|---|---|
| CI에서 Android 빌드·단위 테스트·lint·릴리스(R8) 번들 | ✅ | `.github/workflows/ci.yml` |
| targetSdk 36 (2026-08-31부터 신규 앱 필수), AGP 8.10.1 | ✅ | |
| edge-to-edge(시스템 바 겹침) 대응 | ✅ 🔬 | 실기기 화면 확인 |
| 최초 실행 데이터 제공 동의, 동의 없이도 기기 내 동작 | ✅ | `PrivacySettings` |
| 오프라인 재전송 큐 (이벤트·라벨) | ✅ 🔬 | `UploadQueue`. 지하철 등에서 확인 |
| 감지 기록 위치 (기기 내 저장, 상태·정확도·측정 시각 구분) | ✅ 🔬 | `LocationLogger`. 실외·실내·지하 정확도와 지연 실측 ([확정 범위 5절](PROJECT_SCOPE.md#5-위치가-포함된-로그)) |
| Play Integrity 등록 검증 (앱·서버) | ✅ | 서버 `nfcguard.integrity.mode=google` + 서비스 계정 필요 (👤) |
| 릴리스 서명 (업로드 키) | 👤 | `keytool`로 업로드 키 생성 → `~/.gradle/gradle.properties`에 `nfcguard.signing.*` (커밋 금지) |
| versionCode 관리 | ✅ | 업로드마다 `-Pnfcguard.versionCode=N` 증가 |
| NFC 동작 제한 + 해제·재시도 UX | ⏳ 🔬 | 0단계 결과로 경로 결정 후 구현 |
| 다기종 배터리·백그라운드 생존 테스트 | 🔬 | 갤럭시(배터리 관리 강함), 픽셀, 기타 1종 이상. 하루 배터리 소모 기록 |
| 오탐률 실측 및 임계값 조정 | 🔬 | 통제 실험 수집 (`controlled_experiment`, 테스트 기기 지정 필요) |
| 크래시 수집 SDK | ⏳ 👤 | 계정 생성 후 추가. 추가 시 데이터 보안 양식 갱신 |

## 2단계: 서버 운영

| 항목 | 상태 | 비고 |
|---|---|---|
| Docker 이미지 (비루트 실행), CI 이미지 빌드 | ✅ | `server-java/Dockerfile` |
| PostgreSQL + Flyway 마이그레이션, Hibernate validate | ✅ | CI에서 실제 PostgreSQL로 테스트 |
| 데이터 삭제 API, 보유 기간 자동 삭제(365일) | ✅ | 처리방침과 일치시킬 것 |
| 통제 실험 데이터 수집 (관리자 지정 테스트 기기만) | ✅ | `PUT /api/v1/admin/devices/{token}/test-device` |
| 클라우드 계정·리전 선택 (국내 리전 권장) | 👤 | 국외 리전이면 처리방침에 국외 이전 항목 추가 |
| 도메인 + HTTPS 인증서 | 👤 | 앱 빌드 시 `nfcguard.backendUrl=https://...` |
| 관리형 PostgreSQL, 백업 | 👤 | |
| 비밀값 주입: `NFCGUARD_ADMIN_API_KEY`, `NFCGUARD_DB_*`, `NFCGUARD_NONCE_SECRET`, `GOOGLE_APPLICATION_CREDENTIALS` | 👤 | 저장소·이미지에 넣지 않음 |
| Play Integrity: Google Cloud 프로젝트에서 API 사용 설정, Play Console에 연결, 서비스 계정 | 👤 | 프로젝트 번호를 `nfcguard.cloudProjectNumber`로 |
| 모델 서명 키 생성·보관 | 👤 | `python ml-python/model_export.py --generate-key`, 공개키를 앱 `MODEL_SIGNING_PUBLIC_KEY`에 |
| 모니터링·장애 알림 | ⏳ 👤 | |

## 3단계: 개인정보·정책

| 항목 | 상태 | 비고 |
|---|---|---|
| 개인정보처리방침 초안, 서버 공개 페이지 `/privacy.html` | ✅ 👤 | `[대괄호]` 항목 채우기 + **법률 검토** |
| 앱 내 동의·철회·삭제 기능 | ✅ | 처리방침 5절과 일치 |
| 위치 권한 사전 고지(앱 내) | ✅ | 백그라운드 위치 권한은 쓰지 않음 |
| 위치정보법 검토 | 👤 | 위치를 **기기에만 저장하고 운영자가 수집하지 않는 구조**. 위치기반서비스사업 신고 대상인지 법률 검토로 확정. 서버로 위치를 보내도록 바꾸면 신고·동의 절차가 추가될 수 있음 |
| 스토어 문구: 구현·검증한 기능만, 보장 표현 금지 | ✅ | [STORE_LISTING](release/STORE_LISTING.md) |
| 데이터 보안 양식·포그라운드 서비스 선언 답변안 | ✅ | [PLAY_CONSOLE](release/PLAY_CONSOLE.md) |

## 4단계: Play Console 등록

| 항목 | 상태 | 비고 |
|---|---|---|
| 개발자 계정 (등록비 25달러, 본인 인증) | 👤 | |
| 테스터 12명 확보 | 👤 | 2023-11-13 이후 개인 계정: **비공개 테스트 12명 이상 14일 연속** 후 프로덕션 신청 |
| 그래픽 이미지 1024×500, 실기기 스크린샷 | 👤 | |
| 포그라운드 서비스 시연 영상 | 👤 | 촬영 목록은 [PLAY_CONSOLE 2절](release/PLAY_CONSOLE.md#2-포그라운드-서비스-권한-선언) |
| AAB 업로드 (Play App Signing) | 👤 | `./gradlew :app:bundleRelease` |
| 내부 테스트 → 비공개 테스트(14일) → 프로덕션 신청 → 심사 | 👤 | |

## 출시 후
- 크래시·오탐 응답·리뷰 확인, 판정 개선
- 라벨이 쌓이면 학습 → 서명된 모델 배포 (앱이 하루 1회 확인)
- 매년 Play의 targetSdk 요구 상향(보통 8월 말)에 맞춰 업데이트
