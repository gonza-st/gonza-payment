#!/usr/bin/env bash
# k6-idem 테스트 직전에 실행: 테스트 대상 사용자들의 초기 잔액 스냅샷
set -euo pipefail

BASE_URL="http://api:8080/api"
USERS_FILE="/scripts/results/users.json"
OUTPUT_FILE="/scripts/results/initial-balances.json"

VUS=${TARGET_VUS:-20}
ITERATIONS=${ITERATIONS:-10}
TOTAL_USERS=$(jq '.userIds | length' "$USERS_FILE")

echo "=== 초기 잔액 스냅샷 (VUS=${VUS}, ITERATIONS=${ITERATIONS}) ==="

# k6 테스트와 동일한 로직으로 대상 사용자 및 기대 충전 횟수 계산
# k6: userIds[vu % userIds.length], vu = 1..VUS
declare -A CHARGE_MAP

for vu in $(seq 1 "$VUS"); do
  idx=$(( vu % TOTAL_USERS ))
  uid=$(jq -r ".userIds[$idx]" "$USERS_FILE")
  CHARGE_MAP[$uid]=$(( ${CHARGE_MAP[$uid]:-0} + ITERATIONS ))
done

echo "  대상 사용자: ${#CHARGE_MAP[@]}명"

# 각 사용자의 현재 잔액 조회 → JSON 저장
{
  echo "{"
  FIRST=true
  for uid in "${!CHARGE_MAP[@]}"; do
    balance=$(curl -sf "${BASE_URL}/users/${uid}/wallet" | jq '.balance')
    charges=${CHARGE_MAP[$uid]}
    if [ "$FIRST" = true ]; then FIRST=false; else echo ","; fi
    printf '  "%s": {"initialBalance": %s, "expectedCharges": %s}' "$uid" "$balance" "$charges"
  done
  echo ""
  echo "}"
} | jq . > "$OUTPUT_FILE"

echo "  저장: $OUTPUT_FILE"
echo "=== 스냅샷 완료 ==="
