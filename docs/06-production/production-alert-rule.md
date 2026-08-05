# Phase 6.5.6 Production Alert Rules

> 阈值与 SLO 对齐（production-slo.md）；Owner 为上线值班/对应服务负责人。

## Critical

| 告警 | 阈值 | Owner | Action |
| --- | --- | --- | --- |
| 库存 Redis != MySQL | 对账 diff >0 持续 5min | inventory | 暂停放量 → 对账 REPAIR → 恢复 |
| 超卖 | 任何成功订单 > 库存 | inventory/seckill | 立即停售 → 冻结差异 → 人工核对 |
| Deadlock | Innodb_deadlocks 增量 >0 | inventory | 抓取死锁日志 → 评估事务/索引 |
| MQ lag | >20000 持续 5min | order/inventory | 扩容消费 → 检查 DLQ/异常消息 |
| Gateway error rate | >1% 持续 5min | gateway | 降级限流 → 检查后端/Redis |

## Warning

| 告警 | 阈值 | Owner | Action |
| --- | --- | --- | --- |
| p99 超 SLO | Gateway >150ms / execute >200ms 持续 5min | gateway/seckill | 定位排队/GC/依赖延迟 |
| lock wait 上升 | row_lock_waits p95 >50ms 持续 10min | inventory | 评估热点/分桶分布 |
| consumer retry 上升 | 重试次数 >基线 2× 持续 10min | order/inventory | 检查消息处理异常 |
| 429 误杀率 | >0.1% | gateway | 校验限流 key/阈值 |

## 告警通道

- Critical：电话/IM 值班群；
- Warning：监控面板 + IM；
- 自动动作：Critical 触发限流收紧/停售开关（人工确认后执行）。
