# Production Documents Index（证据索引）

> 用途：从 111 份文档中快速定位核心交付；过程审计稿按阶段归档可查。

## 一、核心交付（必读）

| 文档 | 内容 |
| --- | --- |
| [project-completion-summary.md](./project-completion-summary.md) | 项目完成情况总览（简历视角） |
| [operations-runbook.md](./operations-runbook.md) | 系统使用/部署/压测/运维/回滚操作手册 |
| [personal-project-a1-a5-execution-guide.md](./personal-project-a1-a5-execution-guide.md) | 个人项目 A1-A5 上线执行指南 |
| [ga-release-improvement-plan.md](./ga-release-improvement-plan.md) | GA 改进方案（实施状态） |
| [production-slo.md](./production-slo.md) | SLO（Warning/Critical） |
| [production-alert-rule.md](./production-alert-rule.md) | 告警规则 |
| [observability-hardening.md](./observability-hardening.md) | 可观测性指标冻结 |
| [slow-sql-baseline.md](./slow-sql-baseline.md) | 慢 SQL 基线 |
| [production-config-baseline.md](./production-config-baseline.md) | 生产配置基线 |
| [ga-final-checklist.md](./ga-final-checklist.md) | GA 最终核对清单 |

## 二、性能与容量证据（docs/05-性能优化）

| 文档 | 内容 |
| --- | --- |
| phase6.0性能分析与方案设计.md | 瓶颈分析与方案（P0-P2） |
| phase6.1-performance-report.md | MQ 消费优化 + 入口 RT |
| phase6.2-inventory-hotspot-design.md | 库存分桶设计（N=8） |
| phase6.3-gateway-capacity-report.md | Gateway 容量（700-900 QPS） |
| phase6.4-production-readiness-report.md | 生产就绪评审（G-09/L-07） |
| phase6.5 系列 | RC 硬化/发布冻结 |

## 三、GA 审计链（Phase 6.10-6.23，过程证据）

| 阶段 | 结论 | 关键文档 |
| --- | --- | --- |
| 6.10 | RC1 STABLE | phase6.10-ga-approval-report.md |
| 6.11 | RC1 STABLE | phase6.11-ga-final-readiness-report.md |
| 6.12 | RC1 STABLE | phase6.12-ga-candidate-report.md |
| 6.13 | RC1 STABLE | phase6.13-ga-final-release-report.md |
| 6.14 | RC1 STABLE | phase6.14-ga-blocking-report.md |
| 6.15 | RC1 STABLE | phase6.15-final-ga-release-decision.md |
| 6.16 | RC1 STABLE | phase6.16-final-ga-release-decision.md |
| 6.17 | RC1 STABLE | phase6.17-final-ga-release-decision.md |
| 6.18 | 验证准备完成 | phase6.18-final-ga-readiness-assessment.md |
| 6.19-6.23 | RC1 STABLE（外部资源缺失） | phase6.19/6.21/6.23-final-ga-release-decision.md |

> 结论一致：工程/正确性 PASS；剩余阻塞均为外部生产验证资源。

## 四、演练与 Drill 证据

| 文档 | 内容 |
| --- | --- |
| migration-drill-report.md | 分桶迁移/回滚演练 |
| rollback-drill-report.md | 发布回滚演练 |
| recovery-drill-report.md | Redis/MySQL/MQ 恢复演练 |
| canary-release-report.md | 金丝雀发布演练 |
| security-report.md / security-review.md | 安全审计 |
| e2e-50000-independent-final-report.md | E2E 50000 独立验证（NOT PASS，环境限制） |
| rocketmq-production-capacity-report.md | MQ 容量证据（单机 23% 失败→需生产规格） |

## 五、容量数据归档（docs/06-production/capacity）

| 文件 | 内容 |
| --- | --- |
| gateway-production-capacity.json | G-09 Gateway 容量 |
| production-e2e-capacity-report.json | L-07 E2E 10000 |
| canary-expansion-report.json | Canary 扩容 5% 10000 |
| canary-real-window.json | 真实窗口 113 万请求 |
| rocketmq-capacity-probe-2026-08-09.json | MQ 探针（单机 23.0% 失败） |
| L-08-2026-08-09-1000.json | L-08 1000 档（客户端加固后 PASS） |

## 六、归档建议

Phase 6.15-6.23 的重复环境审计稿可按阶段保留（审计链完整性）；
如需精简目录，可将 `phase6.19*`、`phase6.21*`、`phase6.23*` 移入
`docs/06-production/archive/`，核心结论已浓缩于本索引与阶段总报告。
