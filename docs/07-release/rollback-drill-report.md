# Phase 6.5 RC-06 Rollback Drill Report

## Case A：关闭分桶（enabled=false）

- 行为：回到旧 `inventory` 单行路径 + Lua v1；
- 证据：默认 `mvn clean test` 全量旧路径测试 PASS（SeckillFullFlowIT / SeckillConcurrentIT / CancelRecoverFlowIT 等）；ReleaseDrillIT 回滚迁移（删除分桶后 inventory 汇总行完好）。
- 结论：PASS。

## Case B：Redis Lua v1 回退

- 行为：`RedisStockService.preDeduct/recover`（v1 脚本）在 `enabled=false` 下保持为默认路径；
- 证据：SeckillLoadTest（L-01）与 LuaV2ScriptTest 同时覆盖 v1/v2；禁用开关后旧脚本路径完整回归 PASS。
- 结论：PASS。

## Case C：版本回滚（git revert 模拟）

- 模拟：回退分桶相关 commit（sharding 代码）后，数据库仍保留 V2/V3 表；
- 兼容性：旧代码不读 `inventory_bucket`/`stock_flow.bucket_no`，V2/V3 为增量表/列，无破坏性变更；
- 证据：ReleaseDrillIT 回滚迁移（bucket 表删除后旧表可用）+ 默认套件旧路径 PASS。
- 结论：PASS（DB 兼容）。

## 回滚演练总评

| 场景 | 结果 |
| --- | --- |
| Case A 关闭分桶 | PASS |
| Case B Lua v1 回退 | PASS |
| Case C 版本回滚 + DB 兼容 | PASS |
