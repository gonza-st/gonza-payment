#!/usr/bin/env bash
# =============================================================================
# 부하 테스트
#
# 사용법:
#   bash load-test/scenarios/load/setup.sh          # 기본 100 VU
#   bash load-test/scenarios/load/setup.sh 1000     # 1000 VU
#
# 또는 docker compose 직접:
#   docker compose up k6
#   TARGET_VUS=1000 docker compose up k6
#
# 결과: localhost:3000
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
RESULTS_DIR="$PROJECT_ROOT/load-test/results"

export TARGET_VUS="${1:-100}"

echo "=== 부하 테스트 (VU: ${TARGET_VUS}) ==="
echo ""
docker compose -f "$PROJECT_ROOT/docker-compose.yml" up k6
echo ""
echo "=== 완료 ==="
open "$RESULTS_DIR/load/report.html" 2>/dev/null || echo "  리포트: $RESULTS_DIR/load/report.html"
