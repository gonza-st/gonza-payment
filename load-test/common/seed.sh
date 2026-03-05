#!/usr/bin/env bash
# Docker 컨테이너 안에서 실행되는 데이터 시딩 스크립트
set -euo pipefail

BASE_URL="http://api:8080/api"
RESULTS_DIR="/scripts/results"
USERS_FILE="$RESULTS_DIR/users.json"
USER_COUNT=30000
PARALLEL_JOBS=50

# 시나리오별 결과 디렉토리 자동 생성
mkdir -p "$RESULTS_DIR"
for scenario in /scripts/scenarios/*/; do
  mkdir -p "$RESULTS_DIR/$(basename "$scenario")"
done
cp /scripts/index.html "$RESULTS_DIR/index.html"

# 이미 시딩된 경우 스킵
if [ -f "$USERS_FILE" ]; then
  echo "=== users.json 이미 존재, 시딩 스킵 ==="
  exit 0
fi

# -------------------------------------------------------
# 1. 서버 Health Check
# -------------------------------------------------------
echo "=== 서버 Health Check ==="
MAX_RETRIES=60
for i in $(seq 1 $MAX_RETRIES); do
  if curl -sf "$BASE_URL/products" > /dev/null 2>&1; then
    echo "  서버 준비 완료!"
    break
  fi
  if [ "$i" -eq "$MAX_RETRIES" ]; then
    echo "  서버가 응답하지 않습니다."
    exit 1
  fi
  echo "  서버 대기 중... ($i/$MAX_RETRIES)"
  sleep 2
done

# -------------------------------------------------------
# 2. 상품 등록
# -------------------------------------------------------
echo ""
echo "=== 상품 3개 등록 ==="

register_product() {
  local response
  response=$(curl -sf -X POST "$BASE_URL/products" \
    -H 'Content-Type: application/json' \
    -d "{\"name\": \"$1\", \"price\": $2}")
  echo "$response" | jq -r '.id'
}

P1=$(register_product "스타벅스 아메리카노" 4500)
echo "  스타벅스 아메리카노 (4500원) -> $P1"
P2=$(register_product "배스킨라빈스 싱글" 5500)
echo "  배스킨라빈스 싱글 (5500원) -> $P2"
P3=$(register_product "CU 편의점" 2000)
echo "  CU 편의점 (2000원) -> $P3"

# -------------------------------------------------------
# 3. 사용자 생성
# -------------------------------------------------------
echo ""
echo "=== 사용자 ${USER_COUNT}명 생성 (병렬 ${PARALLEL_JOBS}개) ==="

TEMP_DIR=$(mktemp -d)

create_user() {
  local response
  response=$(curl -sf -X POST "$1/users" \
    -H 'Content-Type: application/json' \
    -d "{\"name\": \"user-$2\"}")
  if [ -n "$response" ]; then
    echo "$response" | jq -r '.userId' > "$3/$2"
  fi
}
export -f create_user

seq 1 $USER_COUNT | xargs -P $PARALLEL_JOBS -I {} bash -c "create_user $BASE_URL {} $TEMP_DIR"

echo "  사용자 ID 수집 중..."

# jq로 users.json 생성
{
  echo '{'
  printf '  "userIds": '
  find "$TEMP_DIR" -type f | sort -t/ -k$(echo "$TEMP_DIR/" | tr -cd '/' | wc -c) -n | xargs -I {} cat {} | jq -R . | jq -s .
  printf ',\n  "productIds": '
  echo "[\"$P1\", \"$P2\", \"$P3\"]"
  echo '}'
} | jq . > "$USERS_FILE"

ACTUAL_COUNT=$(jq '.userIds | length' "$USERS_FILE")
rm -rf "$TEMP_DIR"

echo ""
echo "=== 시딩 완료 ==="
echo "  사용자: ${ACTUAL_COUNT}명"
echo "  상품: 3개"
echo "  저장: $USERS_FILE"
