#!/usr/bin/env bash
set -euo pipefail

# 生成 docs/04-测试体系/coverage-report.md
# 读取各模块 target/site/jacoco/jacoco.xml 的 LINE 计数器，汇总行覆盖率并对比基线。

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="${ROOT_DIR}/docs/04-测试体系"
REPORT_FILE="${REPORT_DIR}/coverage-report.md"
BASELINE_FILE="${REPORT_DIR}/.coverage-baseline"

MODULES=(
  seckill-common
  gateway
  auth-service
  seckill-service
  inventory-service
  order-service
  payment-service
)

parse_line_coverage() {
  local xml="$1"
  local missed covered
  missed=$(grep -o '<counter type="LINE" missed="[0-9]*" covered="[0-9]*"/>' "$xml" \
    | sed -n 's/.*missed="\([0-9]*\)".*/\1/p' \
    | awk '{s+=$1} END {print s+0}')
  covered=$(grep -o '<counter type="LINE" missed="[0-9]*" covered="[0-9]*"/>' "$xml" \
    | sed -n 's/.*covered="\([0-9]*\)".*/\1/p' \
    | awk '{s+=$1} END {print s+0}')
  if [[ -z "$missed" || -z "$covered" || ("$missed" == "0" && "$covered" == "0") ]]; then
    echo "0 0 0.00"
    return
  fi
  local percent
  percent=$(awk -v m="$missed" -v c="$covered" 'BEGIN { if (m+c == 0) printf "0.00"; else printf "%.2f", c*100/(m+c) }')
  echo "$missed $covered $percent"
}

declare -A CURRENT
declare -A PREVIOUS
if [[ -f "$BASELINE_FILE" ]]; then
  while IFS='=' read -r module value; do
    [[ -n "$module" ]] && PREVIOUS["$module"]="$value"
  done < "$BASELINE_FILE"
fi

total_missed=0
total_covered=0
{
  echo "# Seckill-Engine 覆盖率报告"
  echo ""
  echo "生成时间：$(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "统计口径：JaCoCo 行覆盖率（LINE），仅生产代码 src/main/java，排除 DTO/VO/config/*Application。"
  echo ""
  echo "| 模块 | 行覆盖率 | 趋势 | 门禁 | 风险说明 |"
  echo "| --- | --- | --- | --- | --- |"

  for module in "${MODULES[@]}"; do
    xml="${ROOT_DIR}/${module}/target/site/jacoco/jacoco.xml"
    if [[ ! -f "$xml" ]]; then
      echo "| ${module} | 未生成 | - | - | 缺少 jacoco.xml，请先执行 mvn test && mvn jacoco:report |"
      continue
    fi
    read -r missed covered percent <<< "$(parse_line_coverage "$xml")"
    total_missed=$((total_missed + missed))
    total_covered=$((total_covered + covered))
    CURRENT["$module"]="$percent"

    prev="${PREVIOUS[$module]:-}"
    trend="-"
    if [[ -n "$prev" ]]; then
      trend=$(awk -v p="$prev" -v c="$percent" 'BEGIN { d=c-p; if (d>0.005) printf "▲+%.2f", d; else if (d<-0.005) printf "▼%.2f", d; else printf "—" }')
    fi

    gate="≥70%"
    case "$module" in
      inventory-service|seckill-service) gate="≥80%" ;;
      order-service|payment-service) gate="≥75%" ;;
    esac
    risk="PASS"
    if awk -v p="$percent" -v g="${gate//[^0-9]/}" 'BEGIN { exit !(p < g) }'; then
      risk="低于门禁"
    fi
    echo "| ${module} | ${percent}% | ${trend} | ${gate} | ${risk} |"
  done

  overall=$(awk -v m="$total_missed" -v c="$total_covered" 'BEGIN { if (m+c == 0) printf "0.00"; else printf "%.2f", c*100/(m+c) }')
  overall_gate="PASS"
  if awk -v p="$overall" 'BEGIN { exit !(p < 70) }'; then
    overall_gate="低于门禁（<70%）"
  fi
  echo "| **合计** | **${overall}%** | - | **≥70%** | **${overall_gate}** |"
  echo ""
  echo "风险说明：覆盖率低于门禁的模块需在后续阶段补充测试；数据为本地/CI 实测，不作为生产门禁替代。"
} > "$REPORT_FILE"

{
  for module in "${MODULES[@]}"; do
    [[ -n "${CURRENT[$module]:-}" ]] && echo "${module}=${CURRENT[$module]}"
  done
} > "$BASELINE_FILE"

echo "coverage report written: ${REPORT_FILE}"
