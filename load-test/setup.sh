#!/usr/bin/env bash
# =============================================================================
# 부하 테스트 올인원 스크립트
#
# 사용법:
#   bash load-test/setup.sh          # 기본 100 VU
#   bash load-test/setup.sh 1000     # 1000 VU
#   bash load-test/setup.sh 50       # 50 VU
#
# 결과는 load-test/results/ 에 저장됩니다.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

export TARGET_VUS="${1:-100}"

echo "=== 부하 테스트 시작 (동시 사용자: ${TARGET_VUS}명) ==="
echo ""
docker compose -f "$PROJECT_ROOT/docker-compose.yml" up --build k6
echo ""
echo "=== 결과 저장 완료 ==="
open "$SCRIPT_DIR/results/report.html" 2>/dev/null || echo "  report.html: $SCRIPT_DIR/results/report.html"
