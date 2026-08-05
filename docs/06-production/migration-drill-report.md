# Phase 6.5.3 Migration Production Drill Report

> 执行：ReleaseDrillIT（真实 Testcontainers MySQL 8.0.36）。

## 1. 流程执行

| 步骤 | 内容 | 结果 |
| --- | --- | --- |
| 1. 备份 inventory | `CREATE TABLE inventory_bak AS SELECT * FROM inventory` | PASS |
| 2. dry-run 迁移 | 1000/8 计划：8 桶、SUM(total)=1000，不写库 | PASS |
| 3. 真实迁移 | 8 桶 × 125/桶；inventory.total == SUM(bucket.total)=1000 | PASS |
| 4. 不变量 | available + locked == total（每桶与汇总） | PASS |
| 5. Redis 预热 | 删除分桶 key → 按 MySQL 预热 → Redis stock == SUM(bucket.available)==1000 | PASS |
| 6. 回滚演练 | 删除分桶后旧 inventory 汇总行完好且与备份一致 | PASS |

## 2. 关键证明

- **迁移失败不影响旧 inventory**：dry-run 不写库；真实迁移仅在 `inventory.total` 校验通过后执行；回滚删除分桶后旧表数据与备份一致。
- **幂等**：重复迁移（计划一致）不抛错；V2/V3 可重放。

## 3. 结论

正式上线迁移流程可升级、可回滚、可恢复；RC-04/6.5.3 通过。
