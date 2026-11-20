@echo off
chcp 65001 >nul
rem PC에서 NFC Guard 서버 실행 (Windows, 더블클릭). 종료: 창 닫기 또는 Ctrl+C
rem 데이터는 server-java\data\ 에 저장되어 껐다 켜도 유지된다.
cd /d "%~dp0..\..\server-java"
where java >nul 2>nul
if errorlevel 1 (
  echo [!] Java가 없습니다. JDK 17을 설치하세요: https://adoptium.net/temurin/releases/?version=17
  pause
  exit /b 1
)
if "%NFCGUARD_ADMIN_API_KEY%"=="" set NFCGUARD_ADMIN_API_KEY=dev-admin
echo ==================================================================
echo  NFC Guard 서버를 시작합니다 (처음에는 라이브러리 다운로드로 몇 분 걸림)
echo  확인: 브라우저에서 http://localhost:8080/privacy.html
echo  관리자 키: %NFCGUARD_ADMIN_API_KEY%   종료: 창 닫기
echo ==================================================================
call mvnw.cmd -q spring-boot:run
pause
