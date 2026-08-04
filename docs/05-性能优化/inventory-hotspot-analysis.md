# Phase 6.1 Inventory 热点锁分析（Step 4）

> 测量时间：2026-08-04，真实 Testcontainers（MySQL 8.0.36 / RocketMQ 5.3.1），inventory-service 单独启动，消费线程 8。

## 1. 测量方法

- 单 SKU：全部 CREATE_ORDER 消息命中同一 `inventory` 行
- 每档并发：100 / 300 / 500 / 1000，每档 2000 条消息
- 采集：消费 TPS、收敛耗时、`Innodb_row_lock_waits` 增量、`Innodb_deadlocks` 增量、单消息平均事务耗时
- 报告：`integration-test/target/load-reports/L-06-HOTSPOT.json|csv`

## 2. 测量结果

| 并发 | 消费 QPS | 收敛耗时 | 单消息平均耗时 | row_lock_waits 增量 | 死锁增量 |
| --- | --- | --- | --- | --- | --- |
| 100 | 101.33 | 19737 ms | 9.87 ms | 1999 | 0 |
| 300 | 119.62 | 16720 ms | 8.36 ms | 1999 | 0 |
| 500 | 123.75 | 16161 ms | 8.08 ms | 1999 | 0 |
| 1000 | 112.26 | 17815 ms | 8.91 ms | 1999 | 0 |

## 3. 结论

### 3.1 瓶颈确认：单行 FOR UPDATE 串行化

- 每档 2000 条消息中 1999 条发生行锁等待（首条无需等待），即 **每条消息都串行排队**。
- 单事务（SELECT ... FOR UPDATE + CAS UPDATE + stock_flow INSERT + COMMIT）约 8~10 ms，与 100~124 QPS 吻合。
- 消费线程 4 / 8 / 20 在该场景下吞吐基本持平（96~109 QPS），证明瓶颈不在消费调度，而在数据库行锁。

### 3.2 并发拐点

- 300~500 并发达到峰值 ~120 QPS；1000 并发略有回落（线程上下文切换 + Hikari 连接池排队）。
- 死锁为 0：`FOR UPDATE` 单一加锁顺序下无死锁风险。

### 3.3 与 L-03 组合场景的一致性

- 消费线程矩阵（5000 条、单 SKU）：

| order 线程 | inventory 线程 | order 链路 QPS | inventory 链路 QPS | combined 收敛 |
| --- | --- | --- | --- | --- |
| 4 | 20 | 109.18 | 84.57 | 59.1 s |
| 8 | 20 | 207.39 | 96.15 | 52.0 s |
| 16 | 20 | 269.35 | 97.05 | 51.5 s |
| 16 | 4 | 257.52 | 96.03 | 52.1 s |
| 16 | 8 | 290.66 | 109.22 | 45.8 s |

- 结论：order 侧随线程近线性提升（4→16：109→269 QPS）；inventory 侧被单 SKU 行锁封顶在 ~100-124 QPS。
- Phase 6.1 选定 order=16、inventory=8（8 线程与 20 线程持平且线程开销最小）。

## 4. Step 3 批处理评估结论（本阶段不实现）

### 4.1 候选方案

RocketMQ 批量消费（`consumeMessageBatchMaxSize`）可将多条消息一次性拉取/回调，减少调度开销。

### 4.2 评估

1. **框架支持有限**：rocketmq-spring 2.3.1 的 `@RocketMQMessageListener` 为单消息回调模型，批量需要改造 listener 契约（List<MessageExt>），属于消费语义变更。
2. **瓶颈不在调度**：order 线程 4→16 已近线性提升（109→269 QPS），说明消费调度吞吐充足；inventory 瓶颈是单行 `FOR UPDATE`（线程数不敏感），批量消费无法消除行锁串行。
3. **正确性风险**：批量合并事务会破坏冻结语义（每条消息独立幂等、单订单状态正确、单事务 DEDUCT），违背 Phase 6.1“禁止通过降低可靠性换吞吐”。

### 4.3 结论

Phase 6.1 不实现批量消费。若 Phase 6.2 需要，必须作为独立设计：

- `rocketmq.consumer.batch-size` 可配置，默认 `enabled=false`
- 每条消息独立幂等检查、独立状态机流转
- 回归：重复消息 / 异常消息 / 事务失败 / 库存一致性

## 5. 遗留登记

- 单 SKU 行锁吞吐上限（~120 QPS）为当前模型硬边界；解除需 SKU 分片/热点拆分（已按 Phase 6.0 结论推迟到 Phase 6.2 数据闭环后决策）。
- 多 SKU 场景下 inventory 吞吐应随行数增加（行锁分散），本分析为最坏情况。
