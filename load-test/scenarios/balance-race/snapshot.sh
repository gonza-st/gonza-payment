#!/usr/bin/env bash
# k6-balance-race 테스트 직전에 실행: 테스트 대상 사용자들의 초기 잔액 스냅샷
set -euo pipefail

BASE_URL="http://api:8080/api"
USERS_FILE="/scripts/results/users.json"
OUTPUT_DIR="/scripts/results/balance-race"
OUTPUT_FILE="$OUTPUT_DIR/initial-balances.json"
mkdir -p "$OUTPUT_DIR"

VUS=${TARGET_VUS:-20}
TOTAL_USERS=$(jq '.userIds | length' "$USERS_FILE")

echo "=== 잔액 경합 테스트 초기 스냅샷 (VUS=${VUS}) ==="

# k6 테스트와 동일한 로직으로 대상 사용자 계산
# k6: userIds[vu % userIds.length], vu = 1..VUS
declare -A USER_MAP

for vu in $(seq 1 "$VUS"); do
  idx=$(( vu % TOTAL_USERS ))
  uid=$(jq -r ".userIds[$idx]" "$USERS_FILE")
  USER_MAP[$uid]=1
done

echo "  대상 사용자: ${#USER_MAP[@]}명"

# 각 사용자의 현재 잔액 조회 → JSON 저장
{
  echo "{"
  FIRST=true
  for uid in "${!USER_MAP[@]}"; do
    balance=$(curl -sf "${BASE_URL}/users/${uid}/wallet" | jq '.balance')
    if [ "$FIRST" = true ]; then FIRST=false; else echo ","; fi
    printf '  "%s": {"initialBalance": %s}' "$uid" "$balance"
  done
  echo ""
  echo "}"
} | jq . > "$OUTPUT_FILE"

echo "  저장: $OUTPUT_FILE"
echo "=== 스냅샷 완료 ==="
