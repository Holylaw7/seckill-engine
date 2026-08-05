# 生产 SLO（Phase 6.4 冻结）

> 状态：评审基线；数值来自隔离环境实测 + 生产容量模型，Warning/Critical 阈值冻结。

## Gateway

| 指标 | Warning | Critical |
| --- | --- | --- |
| execute p99 | >150ms 持续 5min | >500ms 持续 5min |
| execute error rate | >0.1% | >1% |
| 429 误杀率（限流键维度） | >0.1% | >1% |

## Seckill

| 指标 | Warning | Critical |
| --- | --- | --- |
| execute 成功 RT p99 | >200ms 持续 5min | >500ms 持续 5min |
| 成功吞吐 | <预期 50% 持续 10min | <预期 20% 持续 10min |
| 超卖 | 0（任何一次即 Critical） | 0 |

## MQ

| 指标 | Warning | Critical |
| --- | --- | --- |
| 消费 lag（seckill-order-tx） | >5000 持续 5min | >20000 持续 5min |
| 消费 TPS | <生产 TPS 80% 持续 5min | <生产 TPS 50% 持续 5min |
| 死信/重试异常 | 出现即 Warning | 持续增长即 Critical |

## Inventory

| 指标 | Warning | Critical |
| --- | --- | --- |
| deadlock | 0（任何一次即 Critical） | 0 |
| 超卖 | 0（任何一次即 Critical） | 0 |
| 分桶不变量（available+locked=total） | 对账 diff >0 | diff 持续不消 |
| 行锁等待 | p95 >50ms 持续 10min | p95 >200ms 持续 10min |

## Recovery / 对账

| 指标 | Warning | Critical |
| --- | --- | --- |
| 对账差异修复时长 | >10min | >60min |
| Redis/MySQL 偏差 | >100 持续 10min | >1000 |

## 说明

- Warning：触发告警并进入观察；Critical：触发自动降级/止损流程（限流收紧、熔断、人工介入）。
- SLO 冻结后如需调整，必须走 Phase 6.4+ 评审流程。
