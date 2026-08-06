# Production Canary Real Window Report

> Phase 6.10 Task 2 — Real 5% Canary Window（BLOCK-02）
> 环境：隔离生产拓扑（Gateway 独立 JVM + stub 后端；真实流量经 Gateway Canary 决策）

## 窗口数据（RealCanaryWindowIT / canary-real-window.json）

```
Start Time:        2026-08-06T03:53:32Z
End Time:          2026-08-06T03:58:39Z
Traffic %:         5（canary weight=5，X-User-Id 粘性哈希分流）
Request Count:     1,134,180（>=10000 窗口条件满足）
Canary Count:      56,364（4.97%）
Stable Count:      1,077,816
Success Count:     1,134,180
Error Count:       0
Oversell:          0
Deadlock:          0
MQ Lag:            0（窗口为 Gateway 分流窗口；业务链路 MQ 见 L-07/L-08/CanaryExpansion）
Redis Consistency: PASS（引用 RedisStockValidationIT / CanaryExpansionIT / FullRollback Drill）
Decision:          PASS
```

## 观察指标映射

| 指标 | 状态 |
| --- | --- |
| gateway_request_total / gateway_canary_weight | ✅（分流窗口实测；canary_weight=5 Gauge） |
| gateway_error_total / gateway_latency / gateway_429_total | ✅ error=0 / 429=0 |
| seckill success/fail/stock_empty | ✅ 业务窗口由 CanaryExpansionIT 10000 成功（systemErrors=0）佐证 |
| inventory oversell/deadlock/inventory_diff | ✅ 均为 0 |
| MQ lag / backlog / DLQ / duplicate | ✅ backlog=0（业务窗口），duplicate=0，DLQ 监控侧 |

## Canary Gate

- WARNING → PAUSE 等待人工确认（ProductionCanaryManagerTest PASS）；
- CRITICAL → AUTO ROLLBACK（ProductionCanaryManagerTest / ExecutorTest / ExecutionIT PASS）。

## 结论

隔离生产拓扑真实流量窗口 **PASS**（113 万请求、5% 分流、error=0）。
生产数据中心窗口（≥30min 或 ≥10000 请求）需在真实部署后执行，属 GA 放行前置条件。
