#!/usr/bin/env bash
set -euo pipefail

# Release Check：
#   1) git 工作区干净
#   2) surefire 报告 failures=0 / errors=0
#   3) JaCoCo 覆盖率门禁（整体≥70%，inventory/seckill≥80%，order/payment≥75%）
#   4) 版本信息（commit / branch / build time）
#   5) 生成 docs/04-测试体系/release-check-report.md

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="${ROOT_DIR}/docs/04-测试体系"
REPORT_FILE="${REPORT_DIR}/release-check-report.md"
mkdir -p "$REPORT_DIR"

TESTS_STATUS="FAIL"
COVERAGE_STATUS="FAIL"
GATE_STATUS="FAIL"

# ---------- Check 1: git status ----------
cd "$ROOT_DIR"
DIRTY=$(git -c core.quotepath=false status --porcelain \
  | grep -vE 'docs/04-测试体系/(release-check-report|coverage-report|\.coverage-baseline)' \
  || true)
GIT_STATUS="PASS"
if [[ -n "$DIRTY" ]]; then
  GIT_STATUS="FAIL"
  echo "release-check: working tree not clean:"
  echo "$DIRTY"
fi

# ---------- Check 2: surefire ----------
FAILURES=0
ERRORS=0
REPORT_COUNT=0
while IFS= read -r file; do
  [[ -z "$file" ]] && continue
  REPORT_COUNT=$((REPORT_COUNT + 1))
  line=$(grep -m1 -E '^Tests run: ' "$file" || true)
  f=$(sed -n 's/^Tests run: [0-9]*, Failures: \([0-9]*\).*/\1/p' <<<"$line")
  e=$(sed -n 's/^Tests run: [0-9]*, Failures: [0-9]*, Errors: \([0-9]*\).*/\1/p' <<<"$line")
  FAILURES=$((FAILURES + ${f:-0}))
  ERRORS=$((ERRORS + ${e:-0}))
done < <(find "$ROOT_DIR" -path '*/target/surefire-reports/*.txt' -type f)

if [[ "$REPORT_COUNT" -gt 0 && "$FAILURES" -eq 0 && "$ERRORS" -eq 0 ]]; then
  TESTS_STATUS="PASS"
fi

# ---------- Check 3: coverage ----------
parse_line() {
  local xml="$1"
  local missed covered
  missed=$(grep -o '<counter type="LINE" missed="[0-9]*" covered="[0-9]*"/>' "$xml" \
    | sed -n 's/.*missed="\([0-9]*\)".*/\1/p' \
    | awk '{s+=$1} END {print s+0}')
  covered=$(grep -o '<counter type="LINE" missed="[0-9]*" covered="[0-9]*"/>' "$xml" \
    | sed -n 's/.*covered="\([0-9]*\)".*/\1/p' \
    | awk '{s+=$1} END {print s+0}')
  if [[ -z "$missed" || -z "$covered" ]]; then
    echo "0 0"
    return
  fi
  echo "$missed $covered"
}

COVERAGE_GATES=(
  "seckill-common:70"
  "gateway:70"
  "auth-service:70"
  "seckill-service:80"
  "inventory-service:80"
  "order-service:75"
  "payment-service:75"
)

COVERAGE_DETAILS=""
COVERAGE_OK=1
TOTAL_MISSED=0
TOTAL_COVERED=0
for entry in "${COVERAGE_GATES[@]}"; do
  module="${entry%%:*}"
  gate="${entry##*:}"
  xml="${ROOT_DIR}/${module}/target/site/jacoco/jacoco.xml"
  if [[ ! -f "$xml" ]]; then
    COVERAGE_OK=0
    COVERAGE_DETAILS+="| ${module} | 未生成 jacoco.xml | FAIL |\n"
    continue
  fi
  read -r missed covered <<< "$(parse_line "$xml")"
  TOTAL_MISSED=$((TOTAL_MISSED + missed))
  TOTAL_COVERED=$((TOTAL_COVERED + covered))
  percent=$(awk -v m="$missed" -v c="$covered" 'BEGIN { if (m+c==0) printf "0.00"; else printf "%.2f", c*100/(m+c) }')
  ok="PASS"
  if awk -v p="$percent" -v g="${gate//[^0-9]/}" 'BEGIN { exit !(p < g) }'; then
    ok="FAIL"
    COVERAGE_OK=0
  fi
  COVERAGE_DETAILS+="| ${module} | ${percent}% (gate ${gate}%) | ${ok} |\n"
done

OVERALL=$(awk -v m="$TOTAL_MISSED" -v c="$TOTAL_COVERED" 'BEGIN { if (m+c==0) printf "0.00"; else printf "%.2f", c*100/(m+c) }')
if awk -v p="$OVERALL" 'BEGIN { exit !(p < 70) }'; then
  COVERAGE_OK=0
fi
if [[ "$COVERAGE_OK" -eq 1 ]]; then
  COVERAGE_STATUS="PASS"
fi

# ---------- Check 4: version ----------
COMMIT_ID=$(git rev-parse --short HEAD)
BRANCH=$(git branch --show-current)
BUILD_TIME=$(date '+%Y-%m-%d %H:%M:%S %z')

# ---------- Check 5: gate & report ----------
if [[ "$GIT_STATUS" == "PASS" && "$TESTS_STATUS" == "PASS" && "$COVERAGE_STATUS" == "PASS" ]]; then
  GATE_STATUS="PASS"
fi

{
  echo "# Release Check Report"
  echo ""
  echo "Commit: ${COMMIT_ID}"
  echo "Branch: ${BRANCH}"
  echo "Build time: ${BUILD_TIME}"
  echo ""
  echo "## Git Status"
  echo ""
  echo "Status: ${GIT_STATUS}"
  echo ""
  echo "## Tests"
  echo ""
  echo "Status: ${TESTS_STATUS}"
  echo ""
  echo "Surefire report files: ${REPORT_COUNT}, failures: ${FAILURES}, errors: ${ERRORS}"
  echo ""
  echo "## Coverage"
  echo ""
  echo "Status: ${COVERAGE_STATUS}"
  echo ""
  echo "Overall line coverage: ${OVERALL}% (gate ≥70%)"
  echo ""
  echo "| 模块 | 覆盖率 | 门禁 |"
  echo "| --- | --- | --- |"
  echo -e "${COVERAGE_DETAILS}"
  echo "## Release Gate"
  echo ""
  echo "Gate: ${GATE_STATUS}"
} > "$REPORT_FILE"

echo "release check: git=${GIT_STATUS} tests=${TESTS_STATUS} coverage=${COVERAGE_STATUS} gate=${GATE_STATUS}"
echo "report: ${REPORT_FILE}"

[[ "$GATE_STATUS" == "PASS" ]]
