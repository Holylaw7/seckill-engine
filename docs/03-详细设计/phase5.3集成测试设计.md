# Phase 5.3 集成测试设计确认文档

版本：v1.0（待评审冻结）
分支：feature/phase5-test
基线：Phase 5.1 测试基础设施（commit 5cb53b5）+ Phase 5.2 单元测试补全（237 tests 全绿）

---

## 1. 集成测试环境设计

### 1.1 总体方案

复用 Phase 5.1 冻结的 test-support 基础设施，Testcontainers 启动真实中间件，与生产/开发环境完全隔离：

| 中间件 | 镜像 | 用途 | 隔离性 |
|---|---|---|---|
| MySQL | mysql:8.0.36 | 5 个服务库（seckill_auth/seckill_seckill/seckill_inventory/seckill_order/seckill_payment） | 临时容器，Ryuk 自动回收 |
| Redis | redis:7.2.4 | Lua 真实执行、热点库存、会话、限流数据 | 临时容器，测试命名空间清理 |
| RocketMQ | apache/rocketmq:5.1.4 | 事务消息、消费幂等、重试 | 临时容器，namesrv+broker 单容器 |

禁止：使用开发数据库、本地 Redis、已有 RocketMQ 实例。

### 1.2 MySQL

- 基类容器数据库名 `seckill_test` 仅作为连接入口；
- 集成测试基类在容器就绪后通过 JDBC 创建 5 个业务 schema（`CREATE DATABASE IF NOT EXISTS`），按依赖顺序加载：
  1. `sql/auth-service/V1.0__init.sql` → seckill_auth
  2. `sql/seckill-service/V1.0__init.sql` → seckill_seckill
  3. `sql/inventory-service/V1.0__init.sql` → seckill_inventory
  4. `sql/order-service/V1.0__init.sql` → seckill_order
  5. `sql/payment-service/V1.0__init.sql` → seckill_payment
- 表结构加载完成后加载 `sql/test-data/` 种子数据（见第 3 节）；
- 每个服务上下文通过 `spring.datasource.url` 指向各自 schema（`jdbc:mysql://host:port/seckill_xxx`）。

### 1.3 Redis

- Lua 脚本由各服务 `DefaultRedisScript` Bean 从 classpath 加载，集成测试中直接调用真实 Redis 执行，禁止 Mock；
- Key 清理策略：测试类 `@BeforeEach` 按命名空间清理（`seckill:*`、`auth:session:*`、`risk:*`、`test:*`，使用 SCAN + DEL）；
- 容器每次运行全新启动（无持久化），天然隔离。

### 1.4 RocketMQ

- NameServer：9876；Broker：10911（单容器，沿用 Phase 5.1 冒烟验证通过的启动命令）；
- Topic：`seckill-order-tx`，由 broker 默认 `autoCreateTopicEnable=true` 自动创建，集成测试基类启动后发送一条预热消息确保 Topic/消费组建立；
- Consumer Group（冻结）：`order-consumer`（CREATE_ORDER）、`inventory-consumer`（CREATE_ORDER + CANCEL_ORDER/STOCK_RECOVER）；
- 测试环境通过 `rocketmq.name-server` 指向容器动态端口，`application-test.yml` 与生产配置完全隔离；
- 异步断言使用 Awaitility（测试依赖，仅 integration-test 模块），禁止固定 sleep。

---

## 2. 测试模块组织设计

新增 `integration-test` 模块（纯测试模块）：

```
integration-test
├── pom.xml
└── src/test/java
    └── com/seckill/integration/
        ├── support/        # 多 schema 初始化、命名空间清理、MQ 预热
        ├── flow/           # 正向/失败链路端到端
        ├── lua/            # Redis Lua 真实并发
        ├── mq/             # MQ 幂等/取消/重试
        ├── payment/        # 支付回调链路
        └── fault/          # 异常与一致性场景
```

约束：

- 仅作为测试模块，依赖 test-support（test scope）与各业务模块（test scope，用于启动各自 Spring Boot 上下文）；
- 不引入业务逻辑、不修改任何业务模块；
- 加入父 POM `modules`（monitor 之后）；
- 依赖：spring-boot-starter-test、spring-boot-starter-web、testcontainers（经 test-support 传递）、Awaitility（等待异步消费）。

服务上下文策略：

- 端到端链路按场景启动所需服务（`@SpringBootTest(classes = XxxApplication.class, webEnvironment = RANDOM_PORT)`）；
- Gateway 单独验证过滤器与路由（真实中间件下），端到端链路以 seckill-service HTTP 为入口，模拟 Gateway 已透传 `X-User-Id` / `X-Trace-Id`（Gateway 过滤语义已在 Phase 5.2 单元覆盖，避免多应用动态路由耦合）。

---

## 3. 数据初始化方案

### 3.1 种子数据

| 域 | 初始化内容 |
|---|---|
| auth | 测试用户 `tester`（id=10001，密码 `Test@123`，BCrypt 哈希由测试代码生成后插入，roles=USER）+ 管理用户（roles=USER,ADMIN） |
| seckill | READY 场次 30001（start=now-1h，end=now+1h，limit=1）+ 秒杀商品 20001（价格 99.00） |
| Redis | 预热 `seckill:stock:20001=1000`、`seckill:stock:total:20001=1000` |
| inventory | sku 20001：total=1000、available=1000、locked=0、version=0 |
| order / payment | 空表（仅建表） |

### 3.2 清理策略

三层隔离：

1. **容器级**：Testcontainers 每次全新容器（Ryuk 回收），不存在跨运行残留；
2. **命名空间级**：`@BeforeEach` 清理 Redis `seckill:*`/`auth:session:*`/`risk:*` 与 MySQL 业务表（按表 truncate，跳过依赖表先删后插）；
3. **数据级**：断言只针对测试 ID 命名空间（用户 10001、sku 20001、session 30001、traceId 前缀 `test-`）。

---

## 4. 正向链路测试设计

### 4.1 秒杀成功链路

步骤：

1. `POST /api/v1/auth/login`（tester/Test@123）→ 获取 JWT；
2. 携带 JWT + `X-Trace-Id: test-flow-xxx` 调用 `POST /api/v1/seckill/execute`（sessionId=30001、skuId=20001、quantity=1）；
3. seckill-service 真实执行 Lua 预扣（stock 1000→999，写用户标记）；
4. RocketMQ 事务消息 CREATE_ORDER（本地事务写 pre_deduct）；
5. order-service 消费建单（CREATE→WAIT_PAY，active_key 写入）；
6. inventory-service 消费确认 DEDUCT（available 1000→999、locked 0→1）；
7. 发起支付创建请求（模拟 order-service 调用 payment-service）→ payment_order WAIT_PAY。

断言：

| 数据域 | 期望值 |
|---|---|
| Redis `seckill:stock:20001` | 999 |
| Redis `seckill:user:20001:10001` | 存在 |
| inventory available/locked | 999 / 1（total = available + locked） |
| order | WAIT_PAY，active_key=10001:30001:20001 |
| payment | WAIT_PAY |
| pre_deduct | CONFIRMED（orderId 对应记录） |
| 唯一性 | 订单 1 条、DEDUCT 流水 1 条、幂等记录 1 条 |

### 4.2 秒杀失败链路

- 库存耗尽：预扣至 0 后继续请求 → 30004（STOCK_EMPTY），不产生订单；
- 重复抢购：同一用户第二次 execute → 30005（REPEAT_BUY），Redis 用户标记拦截；
- 未开始/已结束：session 时间边界 → 30001/30002，无副作用。

---

## 5. Redis Lua 真实执行测试

场景：库存 100、并发 200 请求（200 线程同时调用真实 `RedisStockService.preDeduct`，同一 sku 不同 userId）。

验收：

- 成功数 ≤ 100；
- Redis `seckill:stock` ≥ 0，且 = 100 − 成功数；
- 成功用户标记数 = 成功数；
- 数据库事实（对账口径）：total = available + locked；
- 禁止 Mock Redis、禁止自定义解释器，必须真实执行 Lua。

---

## 6. MQ 集成测试设计

### 6.1 重复 CREATE_ORDER

向 `seckill-order-tx:CREATE_ORDER` 发送两条完全相同的消息（messageId/orderId 相同，间隔消费完成后）：

- 订单表仅 1 条；
- inventory 仅 1 条 DEDUCT 流水、available 只扣一次；
- pre_deduct 幂等表仅 1 条；
- 第二次消费不产生任何新增业务效果。

### 6.2 CANCEL_ORDER / 超时取消

1. 构造 WAIT_PAY 且 pay_deadline 已过期的订单；
2. 执行 `TimeoutCloseTask.closeExpiredOrders()`（真实上下文调用）；
3. 断言：order WAIT_PAY→TIMEOUT、active_key 释放、CANCEL_ORDER 发布；
4. inventory 消费 STOCK_RECOVER：locked−1、available+1、RECOVER 流水；
5. seckill-service 内部回补接口被调用（requestId=flowNo），Redis `seckill:stock` 回补、用户标记清除。

### 6.3 消费失败重试

- 向消费者发送非法/无法反序列化消息：消费端抛异常，RocketMQ 按测试配置（maxReconsumeTimes=3）重试后进入丢弃/告警路径；
- 断言：不产生任何业务数据副作用；
- 真实 DB 故障注入不做（容器内难以稳定模拟），以消息解析失败 + 依赖服务缺失场景替代，重试框架行为由 RocketMQ 保证。

---

## 7. Payment 集成测试设计

### 7.1 正常回调

1. 构造 WAIT_PAY 支付单；
2. 调用 payment callback（MockPaymentChannel 验签、时间窗口、金额匹配）：
   - payment_order：WAIT_PAY→PAY_SUCCESS（version CAS、transaction_no、pay_time 写入）；
   - callback_log：VERIFY_OK/PROCESS_SUCCESS；
   - PAY_SUCCESS MQ 发布（测试消费者接收并断言字段）；
   - **历史边界（Phase 5 编写时）**：当时 order-service 尚未实现 PAY_SUCCESS 消费端，断言止于“PAY_SUCCESS 消息已发布”；该缺口已在整体收敛阶段补齐，当前由 `PaymentCallbackFlowIT` 断言订单状态联动。

### 7.2 重复回调

同一 channelTransactionNo 回调两次：

- 第二次命中 `uk_callback_transaction` → 返回成功（兼容渠道重试）；
- 不重复更新 payment_order、不重复发布 PAY_SUCCESS、callback_log 不重复落库。

### 7.3 回调乱序

旧回调/越级回调（如 REFUND_SUCCESS 直接到达 WAIT_PAY 支付单）：状态机拒绝非法流转，payment_order 状态不变、version 不变。

---

## 8. 异常链路设计

| 异常场景 | 验证方式 | 预期 |
|---|---|---|
| MQ 消费失败 | 非法 payload / 依赖缺失 | 消费端抛异常进入重试；不产生部分业务数据 |
| Redis 回补失败 | RecoverClient 返回失败（stub 500） | MySQL 事实正确（locked−1/available+1），Redis 恢复失败进入 repair/告警路径 |
| CAS 冲突 | 两线程并发 confirmDeduct 同 sku 不同订单 | 有限重试后两条 DEDUCT 流水、库存无负值、最终一致 |
| 支付回调乱序 | 越级状态直接调用状态机 | 拒绝非法流转，无 SQL 副作用 |
| 重复消息 | 重复 CREATE_ORDER / PAY_SUCCESS / STOCK_RECOVER | 幂等，无重复订单/流水/库存变化 |

---

## 9. 测试数据隔离

- traceId：`test-{场景}-{随机}`，随请求透传并在断言中校验；
- 固定测试身份：user 10001（tester）、sku 20001、session 30001、topic `seckill-order-tx`（测试容器独占，无生产污染可能）；
- 断言一律限定测试命名空间，禁止全局模糊断言；
- 测试容器与生产环境物理隔离（Docker 临时容器）。

---

## 10. 验收标准

### 成功标准

- 正向秒杀链路全链路通过，各域状态与第 4 节断言一致；
- Redis 与 MySQL 库存一致（total = available + locked，无负库存）；
- MQ 无重复业务结果（重复消息消费后订单/流水/库存均只变化一次）；
- 状态机无非法跳转（所有状态变更经过冻结矩阵）；
- Lua 真实并发：200 并发/100 库存，成功数 ≤ 100；
- 异常场景均落到冻结策略（重试/吞掉/repair/告警），不产生脏数据。

### 失败标准

- 出现超卖（Redis 或 MySQL 负库存）；
- 出现重复订单/重复流水/重复库存扣减；
- MQ 消息丢失导致数据不一致（对账口径不满足）；
- 状态非法跳转被业务代码接受；
- 测试数据污染（非测试命名空间数据被修改）。

---

## 风险点与对策

| 风险 | 对策 |
|---|---|
| RocketMQ 容器启动慢/占用高 | 沿用 Phase 5.1 已验证镜像与启动命令；RocketMQ 场景可独立开关（`testcontainers.rocketmq.enabled=false`） |
| 多服务上下文资源与端口冲突 | 每个服务 `RANDOM_PORT`，按场景最小化启动上下文 |
| MySQL 多 schema 初始化顺序 | 基类按依赖顺序执行建库→建表→种子，幂等脚本（IF NOT EXISTS） |
| 异步消费时序不稳定 | Awaitility 条件轮询（默认超时 30s），禁止固定 sleep |
| order 未实现 PAY_SUCCESS 消费 | 不新增业务能力；断言止于消息发布，登记 Phase 5.8 链路缺口 |
| Gateway 动态路由耦合 | Gateway 单独集成验证；端到端以 seckill-service 为入口（X-User-Id 透传语义已单测覆盖） |

---

提交：`docs(test): phase5.3 integration test design confirmation`
