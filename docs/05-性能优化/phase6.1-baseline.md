# Phase 6.1 优化前基线（Step 1）

> 测量时间：2026-08-04
> 环境：真实 Testcontainers（MySQL 8.0.36 / Redis 7.2.4 / RocketMQ 5.3.1），JDK 21，单机
> 结论性质：用于定位瓶颈与优化前后对比，不作为生产容量结论。

## 1. 当前配置

### 1.1 消费者清单

所有消费者均使用 `@RocketMQMessageListener`（rocketmq-spring-boot-starter 2.3.1 / rocketmq-client 5.2.0）：

| 服务 | 消费组 | Tag | 消费模式 | consumeThreadNumber |
| --- | --- | --- | --- | --- |
| order-service | order-consumer | CREATE_ORDER | CONCURRENTLY | 默认 20 |
| inventory-service | inventory-consumer | CREATE_ORDER | CONCURRENTLY | 默认 20 |
| inventory-service | inventory-recover-consumer | STOCK_RECOVER \|\| CANCEL_ORDER | CONCURRENTLY | 默认 20 |

`DefaultRocketMQListenerContainer` 默认 `consumeThreadNumber=20`、`consumeThreadMax=20`（已通过反编译 2.3.1 确认）。注解整数参数不支持 `${...}` 占位符，`rocketmq.consumer.*` 属性无法覆盖该值。

### 1.2 单消息处理结构

**order-service CREATE_ORDER**

1. 同一事务：idempotent insert → seckill_order insert → order_item insert → 状态流转 UPDATE（CREATE → WAIT_PAY）
2. 事务外：同步 HTTP 调用 seckill-service `/internal/pre-deducts/confirm`（每条消息一次往返）

**inventory-service CREATE_ORDER（DEDUCT）**

1. 同一事务：stock_flow 幂等 exists 检查 → `SELECT ... FOR UPDATE` → CAS UPDATE → stock_flow insert（DEDUCT）
2. 单 SKU 场景下，`FOR UPDATE` 行锁将所有消费串行化

**inventory-service STOCK_RECOVER / CANCEL_ORDER（RECOVER）**

1. 同一事务：stock_flow 幂等 exists 检查 → `SELECT ... FOR UPDATE` → CAS UPDATE → stock_flow insert（RECOVER）
2. 事务外：同步 HTTP 调用 seckill-service `/internal/stocks/recover`

## 2. 当前吞吐实测

复用 Phase 5.5.4 L-03 用例（真实 RocketMQ Producer/Broker/Consumer，无任何代码修改）：

| 指标 | 基线值 |
| --- | --- |
| CREATE_ORDER 生产 QPS | 3084.60 |
| CREATE_ORDER 消费 QPS（order + inventory 双链路合并） | 99.73 |
| 5000 条 CREATE_ORDER 收敛耗时 | 50133 ms |
| 5000 条 CANCEL_ORDER 收敛耗时 | 207 ms |
| 幂等（重复消息只生效一次） | PASS（4/4） |

对应 Phase 6.0 基线：

| 目标 | Phase 5.5.4 基线 | 本次复测 | Phase 6.1 目标 |
| --- | --- | --- | --- |
| O-01 消费 QPS | 96.5 | 99.73 | ≥300 |
| O-02 5000 收敛 | 51.8s | 50.1s | ≤25s |

## 3. 当前瓶颈判断

### 3.1 inventory 行锁串行化（首要怀疑）

- 5000 条消息全部指向同一 SKU，`confirmDeduct` 的 `SELECT ... FOR UPDATE` + CAS UPDATE 使单行成为全局串行点
- 折算单消息事务约 10ms，与实测 ~100 QPS 吻合
- 需要 Step 4 单 SKU 并发对照实验确认锁等待占比

### 3.2 order 事务写放大 + 同步确认

- 每条消息 1 个事务内 3 次 INSERT + 1 次 UPDATE，另加 1 次同步 HTTP confirm 往返
- HTTP 往返在测试本机约 1~3ms，放大后对单线程 RT 有影响，但并发 20 下不构成第一瓶颈

### 3.3 消费线程数

- 当前 20 线程已高于行锁串行吞吐，单纯提升线程数预期收益有限
- 必须通过 4/8/16 对照实验验证是否存在线程竞争/上下文切换反向影响

## 4. 优化前数据存档

- 压测报告：`integration-test/target/load-reports/L-03.json`（本次复测覆盖）
- 用例：`MqConsumerLoadTest`（4 tests，failures=0，errors=0）

## 5. 下一步

Step 2：order-service / inventory-service 分别引入可配置 `consumeThreadNumber`，以 4/8/16 三档对照，每档只改一个变量，独立提交。
