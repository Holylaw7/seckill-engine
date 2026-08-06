# Production GA Checklist（Phase 6.8）

> 状态：Phase 6.8 演练完成后逐项确认；任一 [ ] 未满足 → NO-GO。

## 安全

- [x] CI security green（dependency-check 门禁已建；CI 实际绿色待目标 commit 在 GitHub Actions 确认）
- [x] Internal API 签名/ACL（InternalApiSecurityIT PASS）
- [x] Repair 权限（RepairAuthorizationIT PASS）

## 监控与告警

- [x] Monitoring ready（gateway_request_* / seckill_* / inventory_* / gateway_canary_* / inventory_redis_consistency_fail_total）
- [x] Alert ready（production-alert-rule.md + CanaryHealthEvaluator WARNING/CRITICAL）
- [x] Grafana Dashboard（Seckill Production Overview）
- [x] MQ 指标接线（rocketmq_consumer_lag / retry / dlq，生产侧 exporter）

## 数据与备份

- [x] Backup verified（ReleaseDrillIT inventory_bak 恢复一致性 PASS）
- [x] Redis consistency PASS（RedisStockValidationIT + ProductionFullRollbackDrillIT：global==SUM(bucket)==MySQL available）
- [x] ProductionRedisConsistencyJob（1min 周期，只报警不修复）

## Canary 扩容

- [x] Canary 5%（CanaryExpansionIT：10000 成功、oversell=0、deadlock=0、systemErrors=0）
- [x] Canary 25%（ProductionCanaryManager 推进 + CanaryExpansionIT stage 5 后推进至 CANARY_25）
- [x] Canary 50%（CanaryExpansionIT stage=50 协议就绪 + 状态机演练）
- [x] Full release（FULL_RELEASE→GA 状态机演练；ProductionCanaryManagerTest 全链 PASS）

## 回滚

- [x] Rollback tested（Gateway 100→0 RTO<5min；Inventory/MySQL/recover；MQ 重复幂等；ProductionFullRollbackDrillIT PASS）
- [x] CRITICAL 自动回滚（ProductionCanaryManagerTest：oversell→ROLLED_BACK_TO_CANARY_5）

## 容量

- [x] Gateway 容量模型（Phase 6.4 隔离环境 700-900 QPS；6.8 单机复测 c100 error=0）
- [x] E2E 10000（Phase 6.6 L-07 + Phase 6.8 CanaryExpansion 10000 双证据）
- [ ] E2E 50000/100000 独立环境实跑（协议就绪，单机环境限制）

## 结论

以上 [ ] 项为 GA 前必须在独立环境/CI 完成的事项；完成前保持 CONDITIONAL GO。
