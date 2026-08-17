# Phase 5.3.2 集成测试设计确认文档

版本：v1.0（待评审冻结）
分支：feature/phase5-test
基线：Phase 5.3.1（commit a4ea5ae，集成冒烟 + 秒杀全链路通过）

---

## 0. 基线

### 0.1 单元测试基线（全绿）

| 模块 | 测试数 |
| --- | ---: |
| seckill-common | 34 |
| gateway | 32 |
| auth-service | 38 |
| seckill-service | 41 |
| inventory-service | 31 |
| order-service | 29 |
| payment-service | 32 |
| **合计** | **237** |

### 0.2 集成测试基线（Phase 5.3.1 已验证通过）

| 测试类 | 数量 | 结果 |
| --- | ---: | --- |
| IntegrationEnvironmentSmokeIT | 3 | 通过（MySQL 8.0.36 / Redis 7.2.4 / RocketMQ 5.3.1 真实连通） |
| SeckillFullFlowIT | 1 | 通过（登录 → Lua 预扣 → 事务消息 → 建单 → DEDUCT → 支付创建） |

---

## 1. 测试目标

本阶段在 Phase 5.3.1 全链路打通的基础上，继续验证真实中间件环境下的三类能力：

1. **异常链路**：库存耗尽、场次未就绪、重复抢购、支付回调异常（金额不符/验签失败）均被正确拒绝，且不产生脏数据；
2. **一致性链路**：订单超时取消后库存回补、MQ 重复消息幂等，Redis 与 MySQL 最终一致；
3. **并发安全**：200 并发真实 Lua 预扣不超卖；超时关单与支付成功并发竞争时 version CAS 保证唯一终态。

硬性约束（与 Phase 5.3.1 一致）：

- 禁止 Mock Redis / RocketMQ / MySQL，全部使用 Testcontainers 真实中间件；
- 禁止修改业务逻辑绕过测试；
- 异步结果一律 Awaitility 轮询，禁止 `Thread.sleep`；
- 所有断言限定测试命名空间，禁止全局模糊断言。

---

## 2. 测试范围

| 编号 | 测试类 | 覆盖场景 | 涉及服务上下文 |
| --- | --- | --- | --- |
| SC-01 | `SeckillConcurrentIT` | 200 并发 / 库存 100 真实 Lua 预扣 | seckill + order + inventory |
| SC-02 | `SeckillFailureFlowIT` | STOCK_EMPTY / NOT_READY / REPEAT_BUY | seckill + order + inventory |
| SC-03 | `MqIdempotencyIT` | CREATE_ORDER 重复、STOCK_RECOVER（CANCEL_ORDER）重复 | seckill + order + inventory |
| SC-04 | `CancelRecoverFlowIT` | 超时关单 → CANCEL_ORDER → 库存回补 | seckill + order + inventory |
| SC-05 | `PaymentCallbackFlowIT` | 正常回调 / 重复回调 / 金额异常 / 验签失败 | payment（+ 测试侧 MQ 校验消费者） |
| SC-06 | `OrderTimeoutRaceIT` | 超时关单 vs 支付成功并发竞争（version CAS） | seckill + order + inventory |

不在本阶段范围（登记后续阶段）：

- Phase 5.5 并发压测基线（10,000 QPS）与性能指标；
- RocketMQ 消费重试 / DLQ 场景（Phase 5.3 计划 6.3，本阶段不实现）；
- 故障注入（Redis 宕机、MQ 不可用、MySQL 故障）；
- 安全测试（越权、重放、限流）；
- order-service PAY_SUCCESS 消费端（见第 11 节已知边界）。

---

## 3. 环境方案

复用 Phase 5.1 冻结的 test-support 基础设施，Testcontainers 启动真实中间件：

| 中间件 | 镜像 | 用途 |
| --- | --- | --- |
| MySQL | mysql:8.0.36 | 5 个服务库，容器级隔离 |
| Redis | redis:7.2.4 | Lua 真实执行、热点库存、用户标记 |
| RocketMQ | apache/rocketmq:5.3.1 | 事务消息、幂等、回补消息（namesrv 固定映射 19876，broker 20911） |

要点：

1. 服务上下文通过 `ServiceLauncher` 编程式启动（真实 Spring Boot、随机端口、命令行参数隔离配置），与 Phase 5.3.1 完全一致；
2. 每个场景按最小集启动服务上下文，不启动无关服务（如并发与失败链路不启动 payment、auth）；
3. Topic 固定 `seckill-order-tx`（容器内 `mqadmin updateTopic` 幂等创建）；
4. 测试模块新增支撑类仅放在 `integration-test/src/test/java` 与 `integration-test/src/test/resources`，禁止修改任何业务模块生产配置与代码；
5. 异步断言使用 Awaitility（默认超时 30s，链路场景 60s），禁止固定 sleep。

---

## 4. 测试数据设计

### 4.1 命名空间隔离矩阵

每个测试类使用独立 session/sku/user/orderId/traceId 命名空间，互不共享：

| 测试类 | sessionId | skuId | 用户 | 库存 | 订单号 | traceId |
| --- | --- | --- | --- | --- | --- | --- |
| SeckillConcurrentIT | 31001 | 21001 | 11001~11200（200 人） | Redis 100 / MySQL 100 | 自动生成 | `test-concurrent-{i}-{uuid}` |
| SeckillFailureFlowIT | 31002（READY）<br>31003（INIT） | 21002<br>21003 | 12001~12003 | 0 / 100 | 不产生或自动生成 | `test-failure-*` |
| MqIdempotencyIT | 31004 | 21004 | 13001 | Redis 100 / MySQL 100 | 固定 `9000000001`、`9000000002` | `test-mq-*` |
| CancelRecoverFlowIT | 31005 | 21005 | 14001 | Redis 1000 / MySQL 1000 | 自动生成 | `test-cancel-*` |
| PaymentCallbackFlowIT | 不依赖 | 不依赖 | 15001 | 不依赖 | `test-pay-order-{uuid}` | `test-pay-*` |
| OrderTimeoutRaceIT | 31006 | 21006 | 16001 | Redis 1000 / MySQL 1000 | 自动生成 | `test-race-*` |

### 4.2 初始化方式

1. 各测试类 `@BeforeAll` 通过 JDBC 插入自有 session/sku/inventory 种子（在基类建库建表之后执行），插入前先删除本命名空间旧数据，保证幂等；
2. Redis 预热通过基类 `redisSet` 直接写入 `seckill:stock:{skuId}` 与 `seckill:stock:total:{skuId}`，与 Lua 脚本真实执行；
3. MQ 手工消息通过基类 `sendRocketMqMessage(topic, tag, body)` 发送，使用测试命名空间的 messageId/orderId 作为唯一 key；
4. 支付回调签名按 Mock 渠道契约生成：`HMAC-SHA256(paymentNo|channelTransactionNo|amount|timestamp)`，密钥 `mock-channel-secret`，经 `X-Pay-Sign` / `X-Pay-Timestamp` 请求头传递。

### 4.3 清理策略

1. **容器级**：每次运行全新容器（Ryuk 回收），无跨运行残留；
2. **Redis**：`@BeforeEach` / `@AfterEach` 按 `seckill:*`、`auth:session:*`、`risk:*` 命名空间 SCAN + DEL；
3. **MySQL**：按测试命名空间删除（payment → order → inventory → seckill 依赖顺序），不整表 truncate，避免影响同一 JVM 内其他测试类的静态容器数据；
4. **RocketMQ**：不清理已消费消息（消费组 offset 保留），遗留消息因幂等 + 命名空间断言不受影响；消息 key 一律使用测试命名空间唯一值。

---

## 5. 测试场景设计

### 5.1 SC-01 SeckillConcurrentIT（Lua 真实并发秒杀）

#### 初始化

- session 31001：READY，start=2000-01-01，end=2999-12-31，limit_per_user=1；
- sku 21001：price=99.00；
- inventory：total=100、available=100、locked=0、version=0；
- Redis：`seckill:stock:21001=100`、`seckill:stock:total:21001=100`；
- 用户：11001~11200 共 200 个不同用户。

#### 流程图

```text
200 线程（ExecutorService + CountDownLatch 同时放行）
        │
        ▼
POST /api/v1/seckill/execute（真实 Lua 预扣，seckill 服务）
        │
        ├── 成功（≤100）──▶ RocketMQ 事务消息 ──▶ order 建单 + inventory DEDUCT
        │
        └── 失败（STOCK_EMPTY）──▶ 无 pre_deduct / 无订单
        │
        ▼
Awaitility 等待异步消费收敛
        │
        ▼
DB / Redis 断言 + QPS / 耗时 / 成功率统计
```

#### 断言

| 域 | 断言 |
| --- | --- |
| Redis | `seckill:stock:21001 ≥ 0`，且 = 100 − 成功数；用户标记数 = 成功数 |
| 成功数 | successCount ≤ 100；失败数 ≥ 100 |
| MySQL 库存 | `available_stock + locked_stock = total_stock = 100`，无负值 |
| stock_flow | DEDUCT 流水数 = 成功数 |
| order | 订单数 = 成功数；order_item 数 = 成功数 |
| pre_deduct | 记录数 = 成功数，且 tx_status=SUCCESS、deduct_status=CONFIRMED |
| idempotent | ORDER_CREATE 幂等记录数 = 成功数 |

#### 并发方案与统计

- `ExecutorService` 固定线程池 200，`CountDownLatch` 同时放行，`Future` 收集结果；
- 统计：总耗时、请求吞吐 `QPS = 200 / 耗时秒`、成功率 `successCount / 200`，通过测试日志输出并写入报告（不设性能门禁，正确性断言以 5.1 节为准）；
- 全部异步效果使用 Awaitility 等待，禁止 `Thread.sleep`。

---

### 5.2 SC-02 SeckillFailureFlowIT（秒杀失败链路）

#### 5.2.1 STOCK_EMPTY

前置：session 31002 READY；Redis `seckill:stock:21002=0`、total=100；inventory available=100。

流程：

```text
POST /api/v1/seckill/execute（user=12001, sku=21002, quantity=1）
        │
        ▼
Lua 返回 -1（STOCK_EMPTY）──▶ 响应 code=30004
        │
        ▼
断言：无订单 / 无 pre_deduct / 无消费副作用
```

断言：

- 响应 code = 30004（STOCK_EMPTY）；
- `seckill_order`、`order_item`、`idempotent`（ORDER_CREATE）、`seckill_pre_deduct`、`stock_flow`（DEDUCT）均为 0 条；
- Redis：`seckill:stock:21002` 仍为 0，`seckill:flow:*` 不存在；
- MQ：以“未产生 pre_deduct（本地事务载体）+ 消费端零副作用 + 无 flow key”作为等价断言（失败路径不生成 orderId，无法按 key 检索消息，见第 8 节口径说明）。

#### 5.2.2 NOT_READY

前置：session 31003 status=INIT（INIT/PREHEATING 均映射 30003），Redis 不预热库存。

流程：

```text
POST /api/v1/seckill/execute（user=12002, sku=21003）
        │
        ▼
validateSession 拒绝 ──▶ 响应 code=30003（SESSION_NOT_READY）
        │
        ▼
断言：库存未扣、无订单
```

断言：

- 响应 code = 30003；
- Redis 不存在 `seckill:stock:21003`（未创建扣减键），无用户标记；
- order / pre_deduct / stock_flow 均为 0 条。

#### 5.2.3 REPEAT_BUY

前置：session 31002 READY；Redis stock=100、total=100；inventory available=100。

流程：

```text
第一次 execute（user=12003）──▶ 成功，Redis stock 100→99，用户标记写入
        │
        ▼
第二次 execute（同一用户）──▶ Lua 返回 -2（REPEAT_BUY）──▶ 响应 code=30005
        │
        ▼
断言：Redis 库存不再变化
```

断言：

- 第一次响应 code=0，Redis `seckill:stock:21002=99`，`seckill:user:21002:12003` 存在；
- 第二次响应 code=30005；
- 第二次后 Redis stock 仍为 99，用户标记仍存在；
- 订单数 = 1（仅第一次产生），无重复订单 / 重复 DEDUCT 流水。

---

### 5.3 SC-03 MqIdempotencyIT（MQ 重复消息幂等）

#### 5.3.1 CREATE_ORDER 重复

前置（模拟真实链路事实）：

- session 31004 READY、sku 21004、inventory total=100/available=100/locked=0；
- Redis stock=100、total=100；
- `seckill_pre_deduct` 预插 1 条（messageId、orderId=9000000001、user=13001、DEDUCTED），保证手工消息的 pre-deduct confirm 走通；
- 消息体：`{messageId:"test-mq-create-{uuid}", userId:13001, skuId:21004, sessionId:31004, orderId:"9000000001", quantity:1, amount:9900, timestamp:..., traceId:"test-mq-create-*"}`。

流程：

```text
向 seckill-order-tx:CREATE_ORDER 发送第 1 条（key=orderId）
        │
        ▼
order-consumer 建单 + inventory-consumer DEDUCT（Awaitility 等待）
        │
        ▼
发送第 2 条完全相同消息（messageId/orderId 相同）
        │
        ▼
断言：无任何新增业务效果（幂等）
```

断言：

| 域 | 断言 |
| --- | --- |
| order | `seckill_order` 仅 1 条（order_no=9000000001）；order_item 1 条 |
| idempotent | ORDER_CREATE / messageId 幂等记录仅 1 条 |
| inventory | available=99、locked=1，仅扣一次 |
| stock_flow | DEDUCT 流水仅 1 条（biz_type=ORDER、biz_id=orderId） |
| pre_deduct | confirm 后 deduct_status=CONFIRMED（仅 1 条） |
| 第二次消费 | 等待 10s 轮询期间计数保持 1（无新增） |

#### 5.3.2 STOCK_RECOVER（CANCEL_ORDER）重复

前置：inventory total=100/available=99/locked=1；Redis stock=99、total=100；预插 `seckill_pre_deduct`（CONFIRMED）。

消息体：`{messageId:"test-mq-recover-{uuid}", orderId:"9000000002", userId:13001, skuId:21004, sessionId:31004, quantity:1, reason:"TIMEOUT", timestamp:...}`，tag=CANCEL_ORDER。

流程：

```text
发送第 1 条 CANCEL_ORDER
        │
        ▼
inventory-recover-consumer：MySQL 回补（RECOVER 流水）+ 调用 seckill 回补接口
        │
        ▼
发送第 2 条相同 CANCEL_ORDER（messageId/orderId/requestId 相同）
        │
        ▼
断言：RECOVER 仅一次、Redis 仅回补一次
```

断言：

| 域 | 断言 |
| --- | --- |
| inventory | locked=0、available=100，仅回补一次 |
| stock_flow | RECOVER 流水仅 1 条（biz_type=TIMEOUT、biz_id=9000000002） |
| Redis | `seckill:stock:21004=100`（回补一次；第二次按 requestId=flow_no 幂等无变化） |
| 依赖 | seckill-service 内部回补接口按 `requestId=stock_flow.flow_no` 幂等（见风险 R-01） |

---

### 5.4 SC-04 CancelRecoverFlowIT（订单取消库存恢复链路）

前置：session 31005 READY、sku 21005、库存 1000；执行一次完整秒杀（与 SeckillFullFlowIT 同路径）至 order=WAIT_PAY、inventory locked=1/available=999、Redis stock=999；随后 SQL 将 `pay_deadline` 更新为过去时间（测试数据准备，不修改业务逻辑）。

流程：

```text
完整秒杀链路 ──▶ order WAIT_PAY（pay_deadline 置为过去）
        │
        ▼
手工触发 order 上下文 TimeoutCloseTask.closeExpiredOrders()（自动调度周期调大）
        │
        ▼
order: WAIT_PAY → TIMEOUT（version CAS），active_key 置 NULL，cancel_notify_status=SENT
        │
        ▼
发布 CANCEL_ORDER（reason=TIMEOUT）
        │
        ▼
inventory-recover-consumer：locked 1→0、available 999→1000、RECOVER 流水
        │
        ▼
seckill 内部回补接口：Redis stock 999→1000（依赖 R-01 修复）
        │
        ▼
最终断言：MySQL 库存 = Redis 库存 = 1000
```

断言：

| 域 | 断言 |
| --- | --- |
| order | order_status=TIMEOUT；active_key IS NULL；cancel_notify_status=SENT；version=1 |
| inventory | available=1000、locked=0；RECOVER 流水 1 条（biz_type=TIMEOUT） |
| Redis | `seckill:stock:21005=1000` |
| 一致性 | MySQL available = Redis stock = total = 1000 |
| 幂等 | 补偿任务扫描不到该订单（PENDING 已置 SENT，不会重复发送） |

---

### 5.5 SC-05 PaymentCallbackFlowIT（支付回调链路）

前置：`POST /api/v1/payments/create` 创建支付单（orderNo=`test-pay-order-{uuid}`、amount=99.00、channel=MOCK），状态 WAIT_PAY。每个子场景使用独立 orderNo/paymentNo/transactionNo。

#### 5.5.1 正常回调

```text
POST /api/v1/payments/callback/MOCK（正确签名 + 当前时间戳）
        │
        ▼
验签 → 时间窗口 → 防重放落库 → 金额校验 → CAS WAIT_PAY→PAY_SUCCESS
        │
        ▼
发布 PAY_SUCCESS（测试侧消费者捕获）
```

断言：

| 域 | 断言 |
| --- | --- |
| payment_order | status=PAY_SUCCESS；version 0→1；transaction_no、pay_time 已写入 |
| callback_log | 1 条：verify_result=VERIFY_OK、process_status=SUCCESS |
| MQ | PAY_SUCCESS 消息恰好 1 条，字段 paymentNo/orderNo/userId/amount/transactionNo 与回调一致 |

#### 5.5.2 重复回调

同一 `channelTransactionNo` 连续回调两次：

- 两次 HTTP 均返回 200 `success`；
- callback_log 仅 1 条（`uk_callback_transaction` 防重放）；
- payment_order 只更新一次（version=1，不重复更新）；
- PAY_SUCCESS 只发布 1 条。

#### 5.5.3 金额异常

回调 `amount=88.00`（≠支付单 99.00）：

- 期望：拒绝（HTTP 400），payment_order 保持 WAIT_PAY、version 不变，不发布 PAY_SUCCESS；
- **风险登记（R-03）**：代码走查发现 `CallbackHandler` 在防重放落库后再次 `saveLog(AMOUNT_MISMATCH)` 会触发 `uk_callback_transaction` 唯一键冲突，实测可能返回系统错误而非明确拒绝；若实测确认，按提交规则先 `fix(payment)` 再补 `test(integration): verify amount mismatch callback fix`。

#### 5.5.4 验签失败

回调携带非法 `X-Pay-Sign`：

- 期望：HTTP 400，payment_order 状态不变，不发布 PAY_SUCCESS；
- callback_log 记录 1 条 verify_result=VERIFY_FAIL、process_status=FAILED。

---

### 5.6 SC-06 OrderTimeoutRaceIT（超时关单竞争）

前置：完整秒杀链路至 order=WAIT_PAY（version=0）、inventory locked=1/available=999、Redis stock=999；`pay_deadline` 置为过去。

线程设计：

- 线程 A：`ORDER.context().getBean(TimeoutCloseTask.class).closeExpiredOrders()`（真实扫描 + 关单 + 发布 CANCEL_ORDER）；
- 线程 B：从 order 上下文加载 WAIT_PAY 订单实体后调用 `OrderStateMachine.transition(order, PAY_SUCCESS, null)`，**模拟未来 PAY_SUCCESS 消费端的状态流转**（order-service 当前无 PAY_SUCCESS 消费端，见第 11 节边界；本线程不经过 MQ，只验证真实 DB version CAS 竞争）。

流程：

```text
CountDownLatch 同时放行线程 A / 线程 B
        │
        ├── A 胜出：order=TIMEOUT，active_key=NULL，发布 CANCEL_ORDER → 库存回补
        │
        └── B 胜出：order=PAY_SUCCESS，active_key 保留，不发布 CANCEL_ORDER
        │
        ▼
断言：仅一个终态、version=1、库存与消息仅按胜出方变化一次
```

断言：

| 域 | 断言 |
| --- | --- |
| order | 终态 ∈ {TIMEOUT, PAY_SUCCESS}，且二者必居其一；version=1（CAS 只成功一次） |
| TIMEOUT 胜出 | active_key IS NULL、cancel_notify_status=SENT；RECOVER 流水 1 条；inventory available=1000/locked=0；Redis stock=1000 |
| PAY_SUCCESS 胜出 | active_key 保留（`16001:31006:21006`）；无 RECOVER 流水；inventory available=999/locked=1；Redis stock=999 |
| 消息 | CANCEL_ORDER 相关 RECOVER 流水 ≤1；不允许双状态 / 重复释放 / 重复发送 |

---

## 6. 数据库断言汇总

所有断言均限定测试命名空间（session/sku/user/orderId/traceId），口径：

| 库 | 表 | 关键断言 |
| --- | --- | --- |
| seckill_seckill | seckill_pre_deduct | 成功数 = 预扣记录数；messageId 唯一；deduct_status ∈ {CONFIRMED, RECOVERED} |
| seckill_order | seckill_order / order_item / idempotent | 订单唯一；active_key 语义正确；幂等记录唯一 |
| seckill_inventory | inventory / stock_flow | `available + locked = total`；DEDUCT/RECOVER 流水数精确；无负库存 |
| seckill_payment | payment_order / payment_callback_log | 状态机合法；version CAS 单调；防重放唯一 |

终态一致性口径（SC-04）：`inventory.available_stock = Redis seckill:stock:{skuId} = inventory.total_stock`（无锁定库存时）。

---

## 7. Redis 断言汇总

| Key | 断言 |
| --- | --- |
| `seckill:stock:{skuId}` | ≥ 0；成功预扣后 = 初始 − 成功数；回补后 = 初始值 |
| `seckill:stock:total:{skuId}` | 始终等于投放总量，不允许回补超过 total（Lua 返回 -4） |
| `seckill:user:{skuId}:{userId}` | 成功预扣后存在；重复购买场景保持不变 |
| `seckill:flow:{orderId}` | STOCK_EMPTY / NOT_READY 场景不存在；正常场景发送后存在 |
| `seckill:session:{sessionId}` | 由服务从 DB 加载写入，测试不做伪造 |

说明：用户标记清除（`seckill:user:*`）不在本阶段验收范围，原因见风险 R-02。

---

## 8. MQ 断言汇总

| Topic / Tag | 断言 |
| --- | --- |
| `seckill-order-tx:CREATE_ORDER` | 重复 messageId/orderId 消费后订单、幂等记录、DEDUCT 流水均唯一 |
| `seckill-order-tx:CANCEL_ORDER`（及 STOCK_RECOVER） | 重复消息只产生一次 RECOVER、Redis 只回补一次 |
| `seckill-order-tx:PAY_SUCCESS` | 正常回调恰好 1 条；重复回调仍 1 条；失败回调 0 条 |

PAY_SUCCESS 校验方式：integration-test 新增测试侧 `DefaultMQPushConsumer`（唯一消费组 `integration-pay-success-{uuid}`，仅订阅 PAY_SUCCESS tag），将消息写入内存队列，Awaitility 断言数量与字段。

“STOCK_EMPTY / NOT_READY 无 MQ 消息”的口径说明：失败路径不生成 orderId，RocketMQ 无法按 key 精确检索；以“pre_deduct 未产生（事务消息本地事务载体）+ 消费端零副作用（order/idempotent/stock_flow 均为 0）+ `seckill:flow:*` 不存在”作为等价断言，语义上证明事务消息未发送。

---

## 9. 并发测试方案

### 9.1 SC-01 并发预扣

- 线程模型：`ExecutorService`（200 线程）+ `CountDownLatch(1)` 统一放行 + `Future` 收集；
- 每个线程独立用户（11001~11200）、独立 traceId，同一 sku（21001）；
- 断言：successCount ≤ 100、failCount ≥ 100、Redis stock ≥ 0、`available + locked = total`；
- 统计输出：总耗时、QPS（= 总请求数 / 耗时）、成功率（successCount / 200），写入测试日志供 Phase 5.5 报告引用；
- 禁止 `Thread.sleep`；异步收敛用 Awaitility（60s）。

### 9.2 SC-06 超时关单竞争

- 线程模型：2 线程 + `CountDownLatch(1)` 同时放行；
- 线程 A 走真实 `TimeoutCloseTask`（含 CAS 关单 + 发布消息）；
- 线程 B 走真实 `OrderStateMachine` CAS（模拟 PAY_SUCCESS 消费端）；
- 断言：最终状态二选一、version 只 +1、库存与消息只按胜出方变化一次；
- 通过多次执行（设计建议 5 轮，每轮新订单）增强竞争覆盖，每轮独立命名空间。

---

## 10. 风险清单

| 编号 | 风险 / 发现 | 影响 | 对策 |
| --- | --- | --- | --- |
| R-01 | **seckill-service 内部回补接口 `POST /api/v1/seckill/internal/stocks/recover` 当前未实现**（全仓检索确认，仅 inventory 侧契约与客户端存在） | SC-03、SC-04 的 Redis 回补断言将失败（当前 recover 返回 false 走 repair 告警路径） | 编码阶段第一步登记缺陷，按 `fix(seckill): implement internal stock recover endpoint`（冻结契约：requestId=flow_no 幂等、校验 ≤ total）提交，随后补 `test(integration): verify stock recover endpoint fix`；修复前相关场景预期 RED，不作为流程失败 |
| R-02 | 冻结契约（inventory-service 附录 C.1）回补请求体为 `{requestId, skuId, sessionId, recoverCount}`，**不含 userId**，与 Phase 5.3 设计“回补时清除用户标记”冲突 | 无法按用户清除 `seckill:user:{skuId}:{userId}` 防重标记 | 本阶段 Redis 断言只覆盖库存回补；用户标记清除登记为契约缺口，提交设计评审裁决（追加 userId 或确认标记 TTL 语义），不在本阶段测试中造假绕过 |
| R-03 | `CallbackHandler` 金额异常路径第二次 `saveLog(AMOUNT_MISMATCH)` 与防重放行共用 `channel_transaction_no`，**疑似触发 uk_callback_transaction 冲突**（代码走查发现） | SC-05 金额异常可能返回系统错误而非明确拒绝 | 集成实测确认；若成立按 `fix(payment)` 单独提交（失败原因回填同一日志行或改为更新），再补 verify 提交 |
| R-04 | 历史上 order-service 未实现 PAY_SUCCESS 消费端 | Phase 5 设计时 SC-05 无法验证订单侧支付联动 | 已在整体收敛阶段补齐；当前由 `PaymentCallbackFlowIT` 验证真实消费闭环 |
| R-05 | RocketMQ 容器启动慢 / 固定端口（19876/20911）可能被占用 | 测试环境失败 | 沿用 Phase 5.3.1 已验证镜像与启动命令；端口占用时报告环境问题，不修改业务配置 |
| R-06 | `TimeoutCloseTask` / `CancelNotifyCompensationTask` 自动调度干扰手工触发 | SC-04 / SC-06 竞争不确定 | 服务启动参数将自动调度周期调大（或禁用调度），仅测试代码手工触发任务 |
| R-07 | 同类容器内跨测试类数据 / 消息残留 | 断言误报 | 命名空间隔离 + 幂等设计 + 断言限定测试命名空间；MQ 遗留消息因幂等无副作用 |
| R-08 | 并发测试资源占用（200 线程 + 3 服务上下文） | 环境资源不足导致超时 | 场景最小上下文启动（不启动 payment/auth）；若环境受限可先降级 50 并发（配置化），验收前恢复 200 并发 |

---

## 11. 已知边界（特别登记）

**历史边界：Phase 5 设计冻结时 order-service 尚未实现 PAY_SUCCESS 消费端。**

- 原设计阶段的 PaymentCallbackFlowIT 只验证 payment_order 状态和 `PAY_SUCCESS` 消息发布；
- 该历史限制不代表当前实现状态，整体收敛阶段已新增 Consumer、幂等键和订单状态断言；
- 当前回归由 `PaymentCallbackFlowIT` 覆盖首次回调、重复回调、金额异常、验签异常和订单 `PAY_SUCCESS` 联动。

---

## 12. 测试基础设施调整

允许且仅允许在 `integration-test` 模块内新增：

1. `support/RocketMqTestConsumer.java`：测试侧 MQ 校验消费者（唯一消费组、订阅 PAY_SUCCESS）；
2. `support/TestDataHelper.java`（可选）：按命名空间插入/清理 session、sku、inventory、pre_deduct 等测试数据；
3. `src/test/resources` 下新增测试数据 SQL（如需要），复制仓库级 SQL 的机制保持不变；
4. 各测试类的服务启动参数（禁用风控、调大定时任务周期、服务端口 0 等）。

禁止：

- 修改任何业务模块生产代码、生产配置（application.yml）与测试配置（application-test.yml）；
- 修改 test-support 基类冻结行为；
- 以 Mock 替代真实中间件；
- 测试与修复混合提交。

---

## 13. 提交规划

### 13.1 设计阶段

```text
docs(test): phase5.3.2 integration test design confirmation
```

### 13.2 编码阶段（按场景拆分，逐一验证通过后提交）

```text
1. test(integration): add concurrent seckill test
2. test(integration): add failure flow integration test
3. test(integration): add mq idempotency integration test
4. test(integration): add cancel recover integration test
5. test(integration): add payment callback integration test
6. test(integration): add timeout race integration test
```

### 13.3 生产缺陷提交规则

发现生产缺陷必须：

```text
fix(<module>): xxx
```

随后：

```text
test(integration): verify xxx fix
```

禁止测试与修复混合提交。已知待验证缺陷见 R-01、R-03。

---

## 14. 验收标准

### 成功标准

1. SC-01：200 并发 / 库存 100，successCount ≤ 100、failCount ≥ 100、Redis 无负库存、`available + locked = total`、DEDUCT 流水与订单数 = 成功数；
2. SC-02：STOCK_EMPTY / NOT_READY / REPEAT_BUY 返回冻结错误码且零副作用；
3. SC-03：重复 CREATE_ORDER / CANCEL_ORDER 均幂等，无重复订单、重复流水、重复回补；
4. SC-04：超时关单 → CANCEL_ORDER → 回补全链路收敛，MySQL 库存 = Redis 库存；
5. SC-05：正常 / 重复 / 金额异常 / 验签失败四种回调行为与设计一致，PAY_SUCCESS 发布次数精确；
6. SC-06：竞争后终态唯一、version CAS 生效、库存与消息仅变化一次；
7. 全部场景使用真实中间件 + Awaitility，无固定 sleep，无 Mock。

### 失败标准

- 出现超卖（Redis 或 MySQL 负库存）或库存口径不一致；
- 出现重复订单 / 重复流水 / 重复库存扣减 / 重复回补；
- 状态机非法跳转被业务代码接受；
- 异常链路产生订单或消费副作用；
- 测试数据污染非测试命名空间。

---

本设计确认文档提交信息：

`docs(test): phase5.3.2 integration test design confirmation`
