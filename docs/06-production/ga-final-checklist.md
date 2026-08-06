# GA Final Checklist（Phase 6.9）

> 状态：Phase 6.9 演练完成后的最终上线核对表。
> 标记：`[x]` = 本仓库已验证；`[ ]` = 需生产环境 / GitHub Actions 完成。

## 发布冻结

- [x] RC version frozen（0.1.0-RC1 / tag 0.1.0-RC1 / branch release/RC1）
- [x] 核心模型零改动（状态机 / Lua / 幂等 / MQ 事务 / 分桶 / Schema / Snowflake）

## 安全

- [ ] CI dependency scan green（门禁已建：CVSS>=7 FAIL；需 GitHub Actions 实际绿色确认）
- [x] Security gate PASS（InternalApiSecurityIT / RepairAuthorizationIT / DependencyGateVerificationTest）

## 监控与告警

- [x] Monitoring ready（gateway_request_* / gateway_canary_* / seckill_* / inventory_* / gateway_canary_weight）
- [x] Alert ready（production-alert-rule.md + CanaryHealthEvaluator WARNING/CRITICAL）
- [x] Redis consistency PASS（RedisStockValidationIT / ProductionRedisConsistencyJob / FullRollback Drill）

## Canary 扩容

- [x] Canary 5% PASS（CanaryExpansionIT：10000 成功、oversell=0、deadlock=0、systemErrors=0）
- [x] Canary 25% PASS（ProductionCanaryExecutionIT / ExecutorTest：健康 PASS 推进）
- [x] Canary 50% PASS（同上，容量无退化趋势）
- [x] 100% validation PASS（FULL_RELEASE→GA 状态机 + 全量窗口演练）
- [ ] 真实生产流量窗口（≥30min 或 ≥10000 请求，独立环境执行）

## 正确性

- [x] Oversell = 0
- [x] Deadlock = 0
- [x] Inventory diff = 0（Redis global == SUM(bucket) == MySQL available）
- [x] MQ stable（收敛 300-400ms，backlog=0，重复投递幂等）
- [x] Rollback PASS（Gateway 100→0 RTO<5min；Inventory/MySQL/recover；MQ 幂等）

## 容量

- [x] Capacity accepted（单 Gateway 700-900 QPS，Phase 6.4 隔离环境结论；E2E 10000 双证据）
- [ ] E2E 50000/100000 独立环境实跑（协议就绪，environment limited）

## 运营

- [ ] Operations sign-off（发布窗口 / 值班 / 回滚责任人确认）
- [x] 发布流程演练完成（ProductionCanaryExecutor 5→25→50→100→GA）

## 结论

本仓库演练门禁全部 PASS；`[ ]` 项（CI 实际绿色、真实生产窗口、E2E 高档位、运营签核）
完成前 **保持 RC1 STABLE，不进入 GA 发布**。
