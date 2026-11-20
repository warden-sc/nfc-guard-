#!/usr/bin/env bash
# 서버에 쌓인 학습용 데이터(라벨이 있는 것) 요약 (Mac/Linux)
set -euo pipefail
key="${NFCGUARD_ADMIN_API_KEY:-dev-admin}"
data=$(curl -s -f "http://localhost:8080/api/v1/events/dataset/export" -H "X-Admin-Key: $key") || {
  echo "[!] 서버에 접속하지 못했습니다. run-server 가 실행 중인지 확인하세요."; exit 1; }
count=$(printf "%s" "$data" | grep -c '"id"' || true)
echo "라벨이 있는 이벤트: ${count}건"
printf "%s\n" "$data" | grep -o '"label_source":"[a-z_]*"' | sort | uniq -c || true
echo "(온보딩 6회 등록 -> onboarding 6건 / 알림 응답 -> user_response)"
