# Phase 6.5 RC-04 Production Migration Drill Report

> 执行：ReleaseDrillIT（migrationDrillShouldSplit1000Into8Buckets），真实 Testcontainers MySQL。

## 1. 演练结果

| 步骤 | 断言 | 结果 |
| --- | --- | --- |
| dry-run 迁移（1000/8） | 8 桶计划，SUM(total)=1000，不写库 | PASS |
| 真实迁移 | 8 桶 × 125/桶；inventory.total=1000；SUM(bucket.total)=1000 | PASS |
| 重复迁移（幂等） | 已存在且计划一致时不抛错 | PASS |
| 回滚迁移 | 删除分桶后 inventory 汇总行保留（total=1000） | PASS |

## 2. Migration 幂等性（既有证据）

- V2/V3 为可重放脚本（CREATE IF NOT EXISTS / 条件 ALTER + 条件建索引）；
- `restoreMysqlSchemas` 重放 V1~V3 通过（chaos 套件回归覆盖）。

## 3. Redis Warmup（备份恢复前置）

ReleaseDrillIT `redisWarmupRecoveryShouldMatchMysql`：

- 模拟分桶 key 丢失 → 按 MySQL bucket.total 逐桶 `prepareBucket` 预热；
- 断言：Redis 全局 stock == SUM(bucket.available) == 1000，逐桶一致 → PASS。

## 4. 结论

上线迁移（V2/V3 + 分桶 8 × 125）可重复执行、可回滚、预热后 Redis/MySQL 一致；RC-04 通过。
