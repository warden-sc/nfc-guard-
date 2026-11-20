@echo off
chcp 65001 >nul
rem 서버에 쌓인 학습용 데이터(라벨이 있는 것) 보기 (Windows)
if "%NFCGUARD_ADMIN_API_KEY%"=="" set NFCGUARD_ADMIN_API_KEY=dev-admin
curl -s -f "http://localhost:8080/api/v1/events/dataset/export" -H "X-Admin-Key: %NFCGUARD_ADMIN_API_KEY%" > "%TEMP%\nfcguard_export.jsonl"
if errorlevel 1 (
  echo [!] 서버에 접속하지 못했습니다. run-server.bat 이 실행 중인지 확인하세요.
  pause
  exit /b 1
)
echo 라벨이 있는 이벤트 수:
find /c "\"id\"" < "%TEMP%\nfcguard_export.jsonl"
echo onboarding 건수:
find /c "\"label_source\":\"onboarding\"" < "%TEMP%\nfcguard_export.jsonl"
echo user_response 건수:
find /c "\"label_source\":\"user_response\"" < "%TEMP%\nfcguard_export.jsonl"
echo (온보딩 6회 등록 -^> onboarding 6건 / 알림 응답 -^> user_response)
pause
