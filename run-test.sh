#!/usr/bin/env bash
# =============================================================================
# gonza-payment 시나리오 테스트 실행기
#
# 사용법:
#   ./run-test.sh                       시나리오 목록 출력
#   ./run-test.sh load                  부하 테스트
#   ./run-test.sh idempotency           중복결제 시나리오
#   ./run-test.sh balance-race          잔액 경합 시나리오
#   ./run-test.sh consume-race          기프티콘 동시 Consume 시나리오
#
# 옵션:
#   ./run-test.sh load 200              VU 수 조절
#   ./run-test.sh idempotency 50 20     VU 50, 반복 20회
#   ./run-test.sh balance-race 50 20 3  VU 50, 반복 20회, 동시 3건
#   ./run-test.sh --build load          이미지 재빌드 후 실행
#   ./run-test.sh clean                 결과 파일 초기화
#
# 결과: http://localhost:19000
# =============================================================================

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
RESULTS_DIR="$PROJECT_ROOT/load-test/results"
DC="docker compose -f $PROJECT_ROOT/docker-compose.yml"

# 색상
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

REPORT_BASE="http://localhost:19000"

open_report() {
  local path="${1:-}"
  local url="${REPORT_BASE}/${path}"
  echo -e "  리포트: ${CYAN}${url}${NC}"
  # macOS: open, Linux: xdg-open
  open "$url" 2>/dev/null || xdg-open "$url" 2>/dev/null || true
}

usage() {
  echo ""
  echo -e "${BOLD}gonza-payment 시나리오 테스트${NC}"
  echo ""
  echo -e "  ${CYAN}./run-test.sh ${GREEN}<scenario>${NC} [options]"
  echo ""
  echo -e "${BOLD}시나리오:${NC}"
  echo -e "  ${GREEN}load${NC}            부하 테스트 (충전 → 구매 → 사용)"
  echo -e "  ${GREEN}idempotency${NC}     중복결제 시나리오 (동일 Idempotency-Key 동시 요청)"
  echo -e "  ${GREEN}balance-race${NC}    잔액 경합 시나리오 (동시 구매 잔액 음수 방지)"
  echo -e "  ${GREEN}consume-race${NC}    기프티콘 동시 Consume (조건부 업데이트 검증)"
  echo ""
  echo -e "${BOLD}옵션:${NC}"
  echo -e "  ${YELLOW}--build${NC}         이미지 재빌드 후 실행"
  echo -e "  ${YELLOW}clean${NC}           결과 파일 초기화 (시딩 데이터 포함)"
  echo ""
  echo -e "${BOLD}예시:${NC}"
  echo -e "  ./run-test.sh load                  # 기본 100 VU"
  echo -e "  ./run-test.sh load 200              # 200 VU"
  echo -e "  ./run-test.sh idempotency 50 20     # 50 VU, 20회 반복"
  echo -e "  ./run-test.sh balance-race 30 10 2  # 30 VU, 10회, 동시 2건"
  echo -e "  ./run-test.sh consume-race 30 10 3  # 30 VU, 10회, 동시 3건"
  echo -e "  ./run-test.sh --build idempotency   # 코드 변경 후 재빌드"
  echo ""
  echo -e "${BOLD}결과:${NC}  http://localhost:19000"
  echo ""
}

do_clean() {
  echo -e "${YELLOW}=== 결과 파일 초기화 ===${NC}"
  rm -rf "$RESULTS_DIR"
  echo "  삭제 완료: $RESULTS_DIR"
  echo -e "${YELLOW}=== 다음 실행 시 시딩부터 다시 수행됩니다 ===${NC}"
}

run_load() {
  local vus="${1:-100}"
  export TARGET_VUS="$vus"

  echo -e "${BLUE}=== 부하 테스트 (VU: ${vus}) ===${NC}"
  echo ""
  $DC --profile load up $BUILD_FLAG k6
  echo ""
  echo -e "${GREEN}=== 완료 ===${NC}"
  open_report "load/report.html"
}

run_idempotency() {
  local vus="${1:-20}"
  local iterations="${2:-10}"
  export TARGET_VUS="$vus"
  export ITERATIONS="$iterations"
  export PG_MOCK_DELAY_MS=2000

  echo -e "${BLUE}=== 중복결제 시나리오 테스트 ===${NC}"
  echo ""
  echo "  VU:        ${vus}"
  echo "  반복:      ${iterations}회"
  echo "  PG 지연:   ${PG_MOCK_DELAY_MS}ms"
  echo ""
  $DC --profile idempotency up $BUILD_FLAG k6-idem
  echo ""
  echo -e "${GREEN}=== 완료 ===${NC}"
  open_report "idempotency/report.html"
}

run_balance_race() {
  local vus="${1:-20}"
  local iterations="${2:-10}"
  local concurrent="${3:-2}"
  export TARGET_VUS="$vus"
  export ITERATIONS="$iterations"
  export CONCURRENT_REQUESTS="$concurrent"

  echo -e "${BLUE}=== 잔액 경합 시나리오 테스트 ===${NC}"
  echo ""
  echo "  VU:        ${vus}"
  echo "  반복:      ${iterations}회"
  echo "  동시 구매: ${concurrent}건"
  echo "  상품:      스타벅스 아메리카노 (4,500원)"
  echo ""
  $DC --profile balance-race up $BUILD_FLAG k6-balance-race
  echo ""
  echo -e "${GREEN}=== 완료 ===${NC}"
  open_report "balance-race/report.html"
}

run_consume_race() {
  local vus="${1:-20}"
  local iterations="${2:-10}"
  local concurrent="${3:-2}"
  export TARGET_VUS="$vus"
  export ITERATIONS="$iterations"
  export CONCURRENT_REQUESTS="$concurrent"

  echo -e "${BLUE}=== 기프티콘 동시 Consume 시나리오 테스트 ===${NC}"
  echo ""
  echo "  VU:        ${vus}"
  echo "  반복:      ${iterations}회"
  echo "  동시 요청: ${concurrent}건"
  echo ""
  $DC --profile consume-race up $BUILD_FLAG k6-consume-race
  echo ""
  echo -e "${GREEN}=== 완료 ===${NC}"
  open_report "consume-race/report.html"
}

# ── 인자 파싱 ──────────────────────────────────────────────────────────────

BUILD_FLAG=""
SCENARIO=""
ARGS=()

for arg in "$@"; do
  case "$arg" in
    --build) BUILD_FLAG="--build" ;;
    *)       ARGS+=("$arg") ;;
  esac
done

SCENARIO="${ARGS[0]:-}"

case "$SCENARIO" in
  load)
    run_load "${ARGS[1]:-}"
    ;;
  idempotency)
    run_idempotency "${ARGS[1]:-}" "${ARGS[2]:-}"
    ;;
  balance-race)
    run_balance_race "${ARGS[1]:-}" "${ARGS[2]:-}" "${ARGS[3]:-}"
    ;;
  consume-race)
    run_consume_race "${ARGS[1]:-}" "${ARGS[2]:-}" "${ARGS[3]:-}"
    ;;
  clean)
    do_clean
    ;;
  *)
    usage
    ;;
esac
