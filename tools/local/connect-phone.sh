#!/usr/bin/env bash
# USB로 연결한 폰이 PC 서버(localhost:8080)에 접속하도록 연결 (Mac/Linux)
# 사용법: ./connect-phone.sh            -> 연결만
#         ./connect-phone.sh app-debug.apk -> 기존 앱 삭제 후 설치까지
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

ADB=adb
if ! command -v adb >/dev/null 2>&1; then
  if [ -x "$here/platform-tools/adb" ]; then ADB="$here/platform-tools/adb"; else
    echo "[!] adb가 없습니다. Platform-Tools를 받아 이 폴더(tools/local/platform-tools)에 압축을 푸세요:"
    echo "    https://developer.android.com/tools/releases/platform-tools"
    exit 1
  fi
fi

devices=$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
if [ "$devices" = "0" ]; then
  echo "[!] 연결된 폰이 없습니다. 확인할 것:"
  echo "    1) 폰: 설정 > 휴대전화 정보 > 빌드 번호 7번 탭 > 개발자 옵션 > USB 디버깅 켜기"
  echo "    2) USB 연결 후 폰에 뜨는 'USB 디버깅 허용' 누르기"
  "$ADB" devices
  exit 1
fi

"$ADB" reverse tcp:8080 tcp:8080
echo "[OK] 폰의 localhost:8080 -> PC 서버 연결됨 (USB를 다시 꽂으면 이 스크립트를 다시 실행)"

if [ $# -ge 1 ]; then
  echo "기존 NFC Guard 삭제 후 설치합니다 (CI 빌드는 서명이 매번 달라 덮어쓰기 불가)"
  "$ADB" uninstall com.nfcguard.app >/dev/null 2>&1 || true
  "$ADB" install "$1"
  echo "[OK] 설치 완료. 앱에서 '데이터 제공 동의'를 눌러야 서버로 전송됩니다."
fi
echo "확인: 폰 브라우저에서 http://localhost:8080/privacy.html 이 열리면 연결 성공"
