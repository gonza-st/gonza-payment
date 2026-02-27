#!/usr/bin/env bash
# =============================================================================
# 중복결제 시나리오 테스트
#
# 사용법:
#   bash load-test/scenarios/idempotency/setup.sh              # 기본값
#   bash load-test/scenarios/idempotency/setup.sh 50 20 3      # 50 VU, 20회, 3건 동시
#
# 또는 docker compose 직접:
#   PG_MOCK_DELAY_MS=2000 docker compose up k6-idem
#
# 결과: localhost:3000
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
RESULTS_DIR="$PROJECT_ROOT/load-test/results"

export TARGET_VUS="${1:-20}"
export ITERATIONS="${2:-10}"
export CONCURRENT_REQUESTS="${3:-2}"
export PG_MOCK_DELAY_MS=2000

TOTAL=$((TARGET_VUS * ITERATIONS))

echo "=== 중복결제 시나리오 테스트 ==="
echo ""
echo "  VU:        ${TARGET_VUS}"
echo "  반복:      ${ITERATIONS}회"
echo "  동시 요청: ${CONCURRENT_REQUESTS}건"
echo "  총:        ${TOTAL}건"
echo "  PG 지연:   ${PG_MOCK_DELAY_MS}ms"
echo ""
docker compose -f "$PROJECT_ROOT/docker-compose.yml" up k6-idem
echo ""
echo "=== 완료 ==="
open "$RESULTS_DIR/idempotency-report.html" 2>/dev/null || echo "  리포트: $RESULTS_DIR/idempotency-report.html"
