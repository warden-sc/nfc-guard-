#!/usr/bin/env bash
# PC에서 NFC Guard 서버 실행 (Mac/Linux). 종료: Ctrl+C
# 데이터는 server-java/data/ 에 저장되어 껐다 켜도 유지된다.
set -euo pipefail
cd "$(dirname "$0")/../../server-java"

if ! command -v java >/dev/null 2>&1; then
  echo "[!] Java가 없습니다. JDK 17을 설치하세요: https://adoptium.net/temurin/releases/?version=17"
  exit 1
fi
major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
if [ "${major:-0}" -lt 17 ]; then
  echo "[!] Java ${major} 이 설치되어 있습니다. JDK 17 이상이 필요합니다."
  exit 1
fi

export NFCGUARD_ADMIN_API_KEY="${NFCGUARD_ADMIN_API_KEY:-dev-admin}"
echo "=================================================================="
echo " NFC Guard 서버를 시작합니다 (처음에는 라이브러리 다운로드로 몇 분 걸림)"
echo " 확인: 브라우저에서 http://localhost:8080/privacy.html"
echo " 관리자 키: ${NFCGUARD_ADMIN_API_KEY}   종료: Ctrl+C"
echo "=================================================================="
exec ./mvnw -q spring-boot:run
