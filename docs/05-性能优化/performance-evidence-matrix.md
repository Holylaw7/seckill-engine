# 性能证据矩阵

> 更新日期：2026-08-17  
> 目的：统一记录已实测数据、实验口径和仍缺失的生产级证据。  
> 原则：历史容量数据不因本次功能回归而自动更新；没有采集到的 CPU、内存、P95、P99、Redis QPS 或 MQ TPS 不补填估算值。

## 1. 环境与口径

| 项目 | 记录 |
| --- | --- |
| 主机 | Intel i9-13900H，14 cores / 20 logical processors |
| Docker Engine | 29.7.2，API 1.55 |
| 集成中间件 | MySQL 8.0.36、Redis 7.2.4、RocketMQ 5.3.1 |
| Java | Maven 测试日志使用 JDK 21.0.12 |
| 测试性质 | 本机或隔离拓扑，不能替代生产容量门禁 |
| 当前回归 | `PaymentCallbackFlowIT` 4/4、`BackupRecoveryDrillIT` 2/2 |

## 2. 已有性能证据

| 场景 | 结果 | 关键指标 | 证据/说明 |
| --- | ---: | --- | --- |
| Gateway 安全容量档 | PASS | 约 727.90 QPS；200 并发；p99 390ms；error 0% | `phase6.3-gateway-capacity-report.md`、隔离拓扑容量数据 |
| Gateway 参考上限档 | 仅参考 | 约 1104.92 QPS；p99 1052ms；error 0.16% | 不作为安全容量结论 |
| Redis Lua | PASS | 1847.57 QPS | 单机 Testcontainers 基线；正确性断言通过 |
| Inventory 分桶 N=8 | PASS | 约 360.18 QPS；5000 条约 13.9s 收敛 | `inventory-hotspot-analysis.md` |
| E2E 秒杀 | PASS | 10000 请求；零超卖；历史 L-07 零死锁；幂等和恢复断言通过 | `phase6.6-production-launch-report.md` |
| Canary 窗口 | PASS | 1,134,180 请求；5% 目标分流；实际 4.97%；error 0% | 隔离生产拓扑，不等价真实生产流量 |

## 3. 本次功能回归

本次变更目标是支付成功到订单状态闭环、Redis 丢失恢复，不是容量调优，因此没有重新执行完整压测。

| 测试 | 结果 | 验证内容 |
| --- | --- | --- |
| `PaymentCallbackFlowIT` | 4/4 PASS | 首次回调、重复回调、金额异常、验签异常、PAY_SUCCESS 发布和订单状态闭环 |
| `BackupRecoveryDrillIT` | 2/2 PASS | Redis 全量 key 丢失、按 `available_stock` 重建、对账、Lua 扣减/回补、MQ 重启幂等 |

本次回归没有重新采集以下指标：

- CPU 使用率和 JVM Heap/GC 时间序列；
- Gateway、业务服务的 P50/P95/P99 完整分布；
- Redis command QPS、慢命令和连接等待；
- MySQL QPS、锁等待、死锁时间序列；
- RocketMQ producer/consumer TPS、lag、retry 和 DLQ。

## 4. 生产级验证缺口

| 缺口 | 当前状态 | 完成条件 |
| --- | --- | --- |
| E2E 50000/100000 | NOT RUN | 独立 Load Generator、固定并发和持续时间、完整资源采集 |
| RocketMQ 稳定性 | PENDING | 独立 Namesrv/Broker 集群，验证吞吐、重试、积压、DLQ 和故障恢复 |
| 真实生产 Canary | PENDING | 5% → 25% → 50% → 100%，每档按 SLO 观察并可自动回滚 |
| 统一资源时间序列 | PENDING | Prometheus 采集 Gateway、Redis、MySQL、RocketMQ、JVM 指标 |
| MySQL 锁竞争基线 | PARTIAL | 补充 `performance_schema`、`SHOW ENGINE INNODB STATUS` 和分桶前后对比 |

## 5. 下一轮压测建议

1. 固定 JDK、JVM 参数、实例数、数据量和请求比例；
2. 将 Load Generator 与业务服务、中间件分离部署；
3. 采用 10k → 50k → 100k 请求窗口逐档执行，不把请求总量写成 QPS；
4. 每档同时记录 QPS、TPS、平均 RT、P95、P99、错误率、CPU、Memory、Redis QPS、MySQL 锁等待和 MQ TPS；
5. 以错误率、P99、库存不变量、超卖数和重复订单数共同决定是否通过；
6. 记录瓶颈归因和优化前后对比，再更新本矩阵，不覆盖历史实验结果。

