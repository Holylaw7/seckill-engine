# Phase 6.5 RC Hardening Report

> 分支：phase6.5（RC 冻结点 → release/RC1）；版本：0.1.0-RC1；时间：2026-08-05

## 1. RC 版本信息

- Version：0.1.0-RC1；Commit：见 rc1-build-report.md（冻结基线 `79ed9b6` + 本阶段增量）；
- 依赖/中间件清单：rc1-build-report.md；
- Docker 标签：seckill-{gateway,auth,seckill,order,inventory}:0.1.0-RC1（Launch 阶段构建）。

## 2. Commit 列表（phase6.5）

| Commit | 内容 |
| --- | --- |
| `c11d25f` | chore(release): freeze production configuration |
| `2010306` | build(release): create RC1 artifact manifest |
| `609c09b` | test(migration): add production migration drill |
| `1a37c54` | test(release): add canary validation |
| `3a1c0ff` | feat(observability): add production metrics |
| `a8c9be9` | docs(production): add alert rules |
| `b7f907d` | test(recovery): add backup recovery drill |
| （本报告） | docs(release): phase6.5 RC report |

## 3. 配置冻结结果

- production-config-baseline.md：5 个服务 yml SHA-256 校验和 + Gateway/Redis/MySQL/RocketMQ/分桶基线；
- RC-02 已加固（Redis 3s 超时、Hikari 显式池配置）。

## 4. Migration 结果

- ReleaseDrillIT：1000→8×125，dry-run/真实/幂等/回滚/备份恢复全 PASS（3/3）。

## 5. Canary 结果

- CanaryDrillIT：N=4（106 成功/零超卖）、N=8（150 成功/零超卖）PASS；配合 N=8 360 QPS 基准与 L-07 3000 档 E2E。

## 6. Observability 结果

- Gateway/Seckill/Inventory 指标实现；ObservabilitySmokeIT 3/3 PASS；日志字段要求与敏感信息禁令复核。

## 7. Security 结果

- JWT 缓存边界、黑名单 fail-open、限流语义复核 PASS；
- 依赖漏洞扫描：本环境 NVD 下载超时未完成，登记为生产前置；内部/repair 接口鉴权为上线前置。

## 8. Recovery 结果

- BackupRecoveryDrillIT 2/2：Redis 丢失预热对账一致、MQ consumer 重启幂等；MySQL 备份恢复 PASS。

## 9. Regression 结果

- 本阶段新增测试：ReleaseDrillIT 3/3、CanaryDrillIT 1/1、BackupRecoveryDrillIT 2/2、ObservabilitySmokeIT 3/3 全部 PASS；
- 既有门禁：单元 ≥306、集成 ≥20、Chaos 单类独立 PASS；性能基线（G-09 ≥700 QPS 安全容量、N=8 ≥300 QPS、E2E 3000 档零超卖）保持。
- 最终 `mvn clean test` 在 Commit 8 后执行确认。

## 10. Production Launch Decision

**RC READY（带前置条件）**：

- 生产 Launch 前必须完成：内部接口 mTLS/ACL 与 repair 管理员鉴权；依赖漏洞扫描（CI）；Prometheus/Grafana/Dashboard 监控接线；独立环境容量复核。
- 满足上述条件后按 production-launch-checklist.md 执行；**不自动进入生产发布**，等待 Review。
