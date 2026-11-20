# NFC Guard 문서 안내

프로젝트 소개·화면·현재 상태는 [루트 README](../README.md)에서 관리합니다. 이 파일에 소개를 중복 작성하지 않습니다.

| 문서 | 내용 |
|---|---|
| [확정 범위 및 개발 기준](PROJECT_SCOPE.md) | 2026-11-11 회의의 네 가지 목표, AI·규칙, NFC 제한, UX, 위치 로그 |
| [현재 아키텍처](ARCHITECTURE.md) | 현재 코드의 흐름·데이터 계약·모델 배포와 구현 한계 |
| [개발 환경과 실행](SETUP.md) | Android Studio·서버·ML 설정과 실기기 테스트 |
| [UI 참고 이미지](images/nfc-guard-ui-reference.png) | 팀에서 공유한 기존 라이트·다크·온보딩 화면 |
| [이전 KPI·R&R 제안](KPI_RnR_수정_의사결정안.md) | 과거 논의 기록. 현재 범위와 충돌하면 확정 범위를 우선 |

최신 개발 기준은 `feature/unintended-tap-detection`입니다. 코드의 현재 동작과 앞으로 구현할 목표는 구분해서 읽습니다.
