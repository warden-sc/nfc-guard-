@echo off
chcp 65001 >nul
rem USB로 연결한 폰이 PC 서버(localhost:8080)에 접속하도록 연결 (Windows)
rem 사용법: 더블클릭 -> 연결만 / app-debug.apk 파일을 이 아이콘 위로 끌어다 놓기 -> 설치까지
set "ADB=adb"
where adb >nul 2>nul
if errorlevel 1 (
  if exist "%~dp0platform-tools\adb.exe" (
    set "ADB=%~dp0platform-tools\adb.exe"
  ) else (
    echo [!] adb가 없습니다. Platform-Tools를 받아 이 폴더^(tools\local\platform-tools^)에 압축을 푸세요:
    echo     https://developer.android.com/tools/releases/platform-tools
    pause
    exit /b 1
  )
)
"%ADB%" devices | findstr /R /C:"device$" >nul
if errorlevel 1 (
  echo [!] 연결된 폰이 없습니다. 확인할 것:
  echo     1^) 폰: 설정 - 휴대전화 정보 - 빌드 번호 7번 탭 - 개발자 옵션 - USB 디버깅 켜기
  echo     2^) USB 연결 후 폰에 뜨는 'USB 디버깅 허용' 누르기
  "%ADB%" devices
  pause
  exit /b 1
)
"%ADB%" reverse tcp:8080 tcp:8080
echo [OK] 폰의 localhost:8080 -^> PC 서버 연결됨 (USB를 다시 꽂으면 다시 실행)
if not "%~1"=="" (
  echo 기존 NFC Guard 삭제 후 설치합니다 ^(CI 빌드는 서명이 매번 달라 덮어쓰기 불가^)
  "%ADB%" uninstall com.nfcguard.app >nul 2>nul
  "%ADB%" install "%~1"
  echo [OK] 설치 완료. 앱에서 '데이터 제공 동의'를 눌러야 서버로 전송됩니다.
)
echo 확인: 폰 브라우저에서 http://localhost:8080/privacy.html 이 열리면 연결 성공
pause
