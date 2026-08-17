# Phase 6.5.8 Backup & Recovery Drill Report

> 执行：`BackupRecoveryDrillIT` + `ReleaseDrillIT`（真实 Testcontainers）。
> 本次重点验证 Redis 全量 key 丢失后的可用库存恢复，不把 `total_stock` 错当成可售库存。

## Redis 全量丢失恢复

初始库存不变量：

```text
total_stock = 1000
available_stock = 998
locked_stock = 2
available_stock + locked_stock = total_stock
```

演练步骤：

1. 删除 Redis global、total 和全部 bucket keys；
2. 从 MySQL `inventory.available_stock` 重建 Redis global；
3. 从 MySQL `inventory_bucket.available_stock` 重建各 bucket key；
4. 执行 `BucketReconciliationService.check`；
5. 恢复后执行一次 Lua 扣减和回补，验证服务继续可用。

结果：

```text
Redis available       = 998
Redis total            = 1000
SUM(bucket.available)  = 998
SUM(bucket.locked)     = 2
reconcile              = PASS
Lua deduct             = 998 -> 997
Lua recover            = 997 -> 998
```

## MySQL

- 迁移前执行 `inventory_bak` 备份；
- 回滚删除分桶后旧 inventory 与备份一致（`ReleaseDrillIT.mysqlBackupRestoreDrill`）。

## MQ

- consumer 停止后消息滞留；
- consumer 重启后消费积压；
- 重复 `CREATE_ORDER` 只产生一条 DEDUCT 流水；
- 演练允许基础设施瞬时异常，最终以幂等和库存断言作为通过条件，不能据此宣称全程无死锁。

## 结论

Redis 全量 key 丢失、MySQL 备份恢复和 MQ consumer 重启场景均完成恢复断言。
Redis 恢复必须以 MySQL `available_stock` 为准，并通过
`Redis.available == SUM(bucket.available) == inventory.available` 对账后放量。
