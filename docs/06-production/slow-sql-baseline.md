# Slow SQL Production Baseline（Phase 6.6.5）

> 状态：Testcontainers MySQL 已验证可开启 slow_query_log / long_query_time=1s /
> log_output=TABLE（SlowSqlMonitoringIT PASS）；生产环境由 my.cnf 或配置中心固化。

## 1. 开启配置

```sql
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 1;
SET GLOBAL log_output = 'TABLE';          -- 落 mysql.slow_log，便于查询与告警
-- 生产 my.cnf 固化：
--   slow_query_log=1
--   long_query_time=1
--   log_output=TABLE
--   log_queries_not_using_indexes=0
```

## 2. 监控目标语句（Phase 6.4 已确认热点）

| 模块 | 语句/路径 | 说明 |
| --- | --- | --- |
| inventory | `SELECT ... FROM inventory WHERE sku_id=? FOR UPDATE`（confirmDeduct 串行化） | 行锁等待主要来源 |
| inventory | `SELECT ... FROM inventory WHERE sku_id=? FOR UPDATE`（recoverStock） | 回补热点 |
| inventory | `SELECT ... FROM inventory_bucket WHERE sku_id=? AND bucket_no=? FOR UPDATE`（分桶扣减） | N=8 后单桶竞争 |
| inventory | 对账汇总（SUM(bucket) / inventory / Redis 对比） | 周期任务 |
| order | `INSERT INTO seckill_order` / `order_item`（createOrder） | 建单写放大 |
| seckill | `SELECT ... FROM seckill_sku WHERE session_id=? AND sku_id=?` | execute 高频只读 |

## 3. 监控查询

```sql
-- 慢 SQL TOP（近 1 小时）
SELECT start_time, query_time, rows_examined, rows_sent, db, sql_text
FROM mysql.slow_log
WHERE start_time >= NOW() - INTERVAL 1 HOUR
ORDER BY query_time DESC
LIMIT 50;

-- 锁等待（performance_schema）
SELECT COUNT(*) AS row_lock_waits
FROM performance_schema.table_lock_waits_summary_by_table;

-- 死锁（错误日志 / innodb_metrics）
SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_current_waits';
SHOW GLOBAL STATUS LIKE 'Innodb_deadlocks';
```

## 4. 基线（Phase 6.4 / 6.1 实测，单机 Testcontainers）

| 指标 | 优化前 | 优化后 | 说明 |
| --- | --- | --- | --- |
| row_lock_waits | 18839 | 18602 | 单行锁竞争仍存在，Phase 6.2 分桶后按桶分散 |
| slow SQL 数量 | 4596 | 2480 | SQL 级优化已收敛，仍有下降空间 |
| deadlock | 0 | 0 | 保持 |
| confirmDeduct 事务耗时 | 基线 | 随分桶 N=8 下降（H-01 360 QPS） | 锁等待主路径 |

## 5. 告警建议（联动 production-alert-rule.md）

- Warning：`Innodb_row_lock_current_waits` 持续 >50；
- Critical：`Innodb_deadlocks` 增量 >0 或慢 SQL（>1s）每分钟 >10；
- 观察窗口：5min 聚合，避免瞬时抖动误报。

## 6. 验证

- `SlowSqlMonitoringIT`：开启/读取/恢复 slow_query_log 配置，PASS；
- 生产上线后按第 3 节查询采集 24h 基线，冻结为 Phase 7 容量参考。
