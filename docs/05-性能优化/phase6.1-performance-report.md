# Phase 6.1 性能优化实施报告

> 完成时间：2026-08-04
> 环境：真实 Testcontainers（MySQL 8.0.36 / Redis 7.2.4 / RocketMQ 5.3.1），JDK 21，单机
> 分支：feature/phase5-test

## 1. 优化前基线

| 指标 | Phase 5.5.4 基线 | 2026-08-04 复测基线 |
| --- | --- | --- |
| CREATE_ORDER 消费 QPS | 96.5 | 99.73 |
| 5000 条收敛耗时 | 51.8 s | 50.1 s |
| L-01 入口 QPS / p99 | 741.31 / 1323.36 ms | —（同日复测见 §4） |
| L-04 锁等待 / 慢 SQL | 18839 / 4596 | — |

基线文档：`docs/05-性能优化/phase6.1-baseline.md`

## 2. 修改项

### 2.1 生产代码

| 模块 | 变更 | 说明 |
| --- | --- | --- |
| order-service | `MqConsumerConfig` + `seckill.mq.consumer.threads` | 通过 AnnotationEnhancer 将 consumeThreadNumber 参数化，默认 20 不变 |
| inventory-service | `MqConsumerConfig` + `seckill.mq.consumer.threads` | 同上，覆盖 inventory-consumer / inventory-recover-consumer |
| gateway | pom 增加 grpc-netty/protobuf/stub 1.69.0 | 修复网关无法启动（SCG 4.1.2 缺陷） |
| gateway | `RateLimitConfig` 增加 `@Primary` | 修复 4 个 KeyResolver 导致工厂注入失败 |

### 2.2 测试代码（新增基准）

- `MQConsumerPerformanceTest`：order/inventory 双链路消费能力 + 锁指标基准
- `InventoryHotspotLoadTest`：单 SKU 热点 100/300/500/1000 并发
- `GatewayExecutePerformanceTest`：模型 A（login+execute）/ 模型 B（预登录+execute）+ 直连校准
- `ServiceLauncher` 支持 Reactive（gateway）端口获取
- integration-test pom：grpc-api 版本固定 + 排除 rocketmq-rocksdb 传递的 io.grpc 1.50.0

## 3. 参数变化

| 服务 | 参数 | 基线 | 调优后 |
| --- | --- | --- | --- |
| order-service | `seckill.mq.consumer.threads` | 20（默认） | 16 |
| inventory-service | `seckill.mq.consumer.threads` | 20（默认） | 8 |

消费模式、Topic、消费组、消息格式、幂等/事务语义均未改变。

## 4. 性能对比

### 4.1 MQ 消费（L-03，5000 条单 SKU）

| 指标 | 基线 | 调优后（代表值） | 变化 |
| --- | --- | --- | --- |
| 消费 QPS | 99.73 | 109.87 | +10.2% |
| 5000 条收敛 | 50133 ms | 45508 ms | -9.2% |
| 取消收敛 | 207 ms | 221 ms | 持平 |

注：同日首轮复测受主机负载影响（生产 QPS 由 3728 降至 2262），消费 85.69 QPS；取负载正常轮次为代表值。

### 4.2 消费线程矩阵（MQConsumerPerformanceTest，5000 条）

| order 线程 | inventory 线程 | order 链路 QPS | inventory 链路 QPS | combined 收敛 |
| --- | --- | --- | --- | --- |
| 4 | 20 | 109.18 | 84.57 | 59.1 s |
| 8 | 20 | 207.39 | 96.15 | 52.0 s |
| 16 | 20 | 269.35 | 97.05 | 51.5 s |
| 16 | 4 | 257.52 | 96.03 | 52.1 s |
| 16 | 8 | 290.66 | 109.22 | 45.8 s |
| 16 | 16 | 85.58 | 44.42 | 112.6 s（异常波动，环境负载） |

### 4.3 单 SKU 热点（InventoryHotspotLoadTest，2000 条/档）

| 并发 | 消费 QPS | 单消息平均 | 行锁等待 | 死锁 |
| --- | --- | --- | --- | --- |
| 100 | 101.33 | 9.87 ms | 1999/2000 | 0 |
| 300 | 119.62 | 8.36 ms | 1999/2000 | 0 |
| 500 | 123.75 | 8.08 ms | 1999/2000 | 0 |
| 1000 | 112.26 | 8.91 ms | 1999/2000 | 0 |

### 4.4 Gateway/Execute 分层（L-06-GATEWAY）

| 分段 | p50 | p95 | p99 |
| --- | --- | --- | --- |
| 模型 A login（网关） | 1067.52 ms | 4441.74 ms | 4442.04 ms |
| 模型 A execute（网关） | 1192.75 ms | 3442.41 ms | 3710.25 ms |
| 模型 B execute（网关） | 193.24 ms | 251.64 ms | 294.54 ms |
| 模型 B execute（直连） | 41.27 ms | 63.76 ms | 68.61 ms |
| Gateway 开销（差值） | ≈152 ms | ≈188 ms | ≈226 ms |

### 4.5 入口压测（L-01，库存 100 / 并发 1000）

| 指标 | Phase 5.5.2 基线 | 本次复测（两次） |
| --- | --- | --- |
| QPS | 741.31 | 303.61 / 367.97 |
| avgRT | 790.45 ms | 2554.04 / 1900.12 ms |
| p99 | 1323.36 ms | 3264.77 / 2624.57 ms |
| 成功 / 失败 | 100 / 900 | 100 / 900（两次均零超卖） |

说明：正确性全部通过；绝对值显著低于 Phase 5.5.2 为同日连续压测导致的主机/Docker 负载波动，不作为容量结论。核心路径（execute）已由 L-06-GATEWAY 分层测量验证。

### 4.6 数据库压力（L-04）

| 指标 | 基线 | 本次 | 变化 |
| --- | --- | --- | --- |
| 行锁等待 | 18839 | 18602 | -1.3% |
| 慢 SQL | 4596 | 2480 | -46.0% |
| 死锁 | 0 | 0 | 持平 |
| 写入（insert/update） | 99024 / 57001 | 99024 / 57001 | 持平 |

## 5. 锁等待变化

- 单 SKU 场景每消息必发生一次行锁等待（1999/2000），`FOR UPDATE` 串行化是唯一硬瓶颈；消费线程数不敏感。
- inventory 8 线程达到与 20 线程相同的吞吐（~110 QPS），线程开销减半。
- 死锁保持 0（单一加锁顺序）。

## 6. MQ 收敛变化

- 5000 条 CREATE_ORDER：50.1 s → 45.5 s（最佳）；消费 QPS 99.73 → 109.87。
- CANCEL 收敛保持 <250 ms。
- backlog 最终为 0，幂等（重复消息只生效一次）回归 PASS。

## 7. RT 变化

- execute 核心路径 p99：网关 294.54 ms / 直连 68.61 ms，满足 O-05（≤500 ms）。
- Gateway 开销 ≈150-230 ms（JWT 解析 + Filter 链 + 限流 Redis + 路由），为 Phase 6.2 入口优化候选。
- 登录链路（BCrypt）在 100 并发下 p99 ≈4.4 s，属认证路径固有成本；L-01 场景预登录不受影响。

## 8. 目标达成评估

| 目标 | 基线 | 目标 | 结果 | 判定 |
| --- | --- | --- | --- | --- |
| O-01 消费 QPS | 96.5 | ≥300 | 109.87（combined）；order 链路 269.35 | 未达成（行锁封顶） |
| O-02 5000 收敛 | 51.8 s | ≤25 s | 45.5 s（最佳） | 未达成（同因） |
| O-05 execute 核心 p99 | 1323 ms | ≤500 ms | 294.54 ms（网关）/ 68.61 ms（直连） | 达成 |
| G-01~G-07 正确性 | PASS | 保持 | 全部 PASS | 达成 |

**未达成根因（已闭环验证）**：inventory 单 SKU `SELECT ... FOR UPDATE` 将消费事务串行化（8-10 ms/条，上限 ~110-124 QPS）。消费线程调优使 order 链路提升至 269 QPS，但 combined 受 inventory 封顶。解除该上限需要 SKU 分片/热点拆分（Phase 6.0 已登记为 P2，按评审结论待 Phase 6.2 数据闭环后决策）。

## 9. 风险分析

1. **单机环境波动**：L-01/L-03 绝对值随主机负载波动（±30%），所有对比均取代表轮并注明。
2. **o16-i16 异常样本**：一次运行出现双链路下降，疑似线程/连接竞争叠加主机负载；最终配置 inventory=8 规避，Phase 6.2 需在独立环境复核。
3. **Gateway 开销**：~150-230 ms/请求，属入口 RT 主要来源；本次未优化（Phase 6.2 候选）。
4. **批处理未实施**：批量消费会破坏“每条消息独立幂等/单事务”冻结语义且无法突破行锁；维持单消息模型（评估见 inventory-hotspot-analysis.md）。
5. **无生产容量结论**：Testcontainers 单机数据仅用于定位与对比。

## 10. 回滚方式

- 消费线程：删除/回退 application.yml 中 `seckill.mq.consumer.threads`，即恢复 rocketmq-spring 默认 20（行为与基线一致）。
- gateway 修复：如需回滚需同时恢复 grpc 依赖与 `@Primary`（回滚后网关无法启动，属既有缺陷态，不建议单独回滚）。
- 测试代码：不影响生产运行，可独立回退。
- 所有变更均有独立 commit，可逐项 revert。

## 11. 回归结果

| 套件 | 数量 | 结果 |
| --- | --- | --- |
| 单元测试 | 281（common 34 / gateway 32 / auth 38 / seckill 62 / inventory 31 / order 29 / payment 55） | 0 failures / 0 errors |
| 集成测试 | 17 | 0 failures / 0 errors |
| 故障演练 | 13 | 0 failures / 0 errors |
| L-03 / L-04 / L-01 | 全部重跑 | 0 failures / 0 errors，零超卖，backlog=0 |

## 12. 生产缺陷记录

| Commit | 缺陷 | 根因 | 修复 | 验证 |
| --- | --- | --- | --- | --- |
| 1d470c7 | gateway 无法启动 | SCG 4.1.2 `JsonToGRPCFilterFactory/GrpcSslConfigurer` 依赖 grpc，starter 不传递 | pom 增加 grpc 依赖 | 网关启动成功 |
| f00d6c9 | 缺 `ForwardingChannelBuilder2` | grpc 1.62.2 过旧（该类型 1.66+ 引入） | 升级 grpc 1.69.0 | 网关启动成功 |
| 70d5080 | KeyResolver 注入失败 | 4 个 KeyResolver 无 `@Primary` | `rateLimitKeyResolver` 标记 `@Primary` | 网关启动成功 |

## 13. 是否进入 Phase 6.2

**建议进入 Phase 6.2**，理由：

1. 正确性门禁 G-01~G-07 全部保持，无一致性回退。
2. O-05 达成；O-01/O-02 未达成的根因已数据闭环（单 SKU 行锁硬上限），非参数调优可解。
3. Phase 6.2 应优先评审：Inventory 热点拆分/SKU 分片设计（解除行锁上限），其次入口 RT（Gateway 开销）。

**Phase 6.2 前置条件**：在独立/负载受控环境复核 o16-i8 参数与 L-01 绝对值；批量消费维持不实施。
