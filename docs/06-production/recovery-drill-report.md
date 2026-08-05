# Phase 6.5.8 Backup & Recovery Drill Report

> 执行：BackupRecoveryDrillIT + ReleaseDrillIT（真实 Testcontainers）。

## Redis

- 模拟分桶 key + 全局 key 丢失 → 按 MySQL 预热（prepare + prepareBucket）→ `BucketReconciliationService.check` PASS，Redis stock == SUM(bucket.available) == 1000。

## MySQL

- 迁移前 `inventory_bak` 备份；回滚删除分桶后旧 inventory 与备份一致（ReleaseDrillIT mysqlBackupRestoreDrill）。

## MQ

- consumer 停止 → 消息滞留 → 重启后消费积压且 DEDUCT 只生效一次（重复投递幂等）。

## 结论

Redis/MySQL/MQ 三类故障均可恢复且数据最终一致；6.5.8 通过。
