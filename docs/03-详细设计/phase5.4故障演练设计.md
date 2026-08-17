# Phase 5.4 故障演练设计确认文档

版本：v1.0（待评审冻结）
分支：feature/phase5-test
基线：Phase 5.3.2（commit c1d1a76，单元 237 + 集成 17 = 254 tests，failures=0，errors=0）

---

## 0. 基线

### 0.1 冻结架构

```text
Nginx（限流/负载）
    │
    ▼
Gateway（鉴权/限流/黑名单/TraceId）
    │
    ▼
Service（auth → seckill → order / inventory / payment）
    │
    ├── MySQL 8.x（业务事实源：5 个 schema）
    ├── Redis 7.x（热点库存 Lua、会话、限流、防重标记）
    └── RocketMQ 5.x（事务消息、异步消费、最终一致）
```

### 0.2 测试基线

| 项 | 数量 |
| --- | ---: |
| 单元测试 | 237 |
| 集成测试（Phase 5.3.1/5.3.2） | 17 |
| 合计 | 254 |
| failures / errors | 0 / 0 |

### 0.3 冻结约束（本阶段红线）

- 不修改状态机（OrderStateMachine / PaymentStateMachine）；
- 不新增接口（对内、对外均不加）；
- 不扩大业务边界；
- 不修改数据库设计（表、字段、约束、索引）；
- 不修改业务逻辑绕过故障；
- 不 Mock Redis / MySQL / RocketMQ，故障全部通过真实中间件注入。

---

## 1. 演练目标

在真实中间件环境下验证冻结架构的故障行为是否符合设计预期：

1. **快速失败**：Redis/MySQL/RocketMQ 不可用时，秒杀与交易链路快速失败，不产生脏数据；
2. **可恢复**：中间件恢复后，悬挂的 MQ 消息、未完成的消费、待支付订单可继续收敛；
3. **一致性**：Redis stock、inventory available/locked、order 状态、payment 状态、MQ 消费状态在任何故障后满足冻结口径；
4. **幂等与补偿**：故障重试不产生重复业务效果，需要补偿的场景落到既有冻结策略（对账/告警/repair）。

---

## 2. 演练范围与禁止项

### 2.1 范围

| 域 | 场景 |
| --- | --- |
| Redis | 秒杀期间 Redis 不可用；Redis 恢复与库存一致性检查；Redis 宕机期间取消回补 |
| MySQL | 数据库连接异常；写入失败；事务回滚 |
| RocketMQ | producer 发送失败；consumer 异常；重试机制；幂等验证 |
| 服务异常 | order-service 异常；inventory-service 异常；payment-service 异常 |
| 数据一致性 | Redis stock / inventory available+locked / order 状态 / payment 状态 / MQ 消息状态 |

### 2.2 禁止项

1. 禁止修改状态机与状态流转语义；
2. 禁止新增任何接口（包括内部接口）；
3. 禁止修改数据库设计（表结构、约束、唯一键、索引）；
4. 禁止扩大业务边界（如新增 PAY_SUCCESS 消费端、扩展 recover 契约）；
5. 禁止以 Mock 替代真实故障注入；
6. 禁止使用 `Thread.sleep` 等待故障/恢复，统一 Awaitility。

---

## 3. 故障注入与恢复方式总览

| 故障 | 注入方式 | 恢复方式 | 说明 |
| --- | --- | --- | --- |
| Redis 不可用 | `REDIS.stop()`（Testcontainers 容器停止） | `REDIS.start()`（同容器重启，无持久化） | 重启后热点库存键丢失，需测试侧按测试命名空间重新预热 |
| MySQL 连接异常 | `MYSQL.stop()` | `MYSQL.start()` | 容器文件系统保留，数据不丢 |
| MySQL 写入失败 | root 连接执行 `REVOKE INSERT ... ON <schema>.* FROM 'test'@'%'` | `GRANT ALL PRIVILEGES ON *.* TO 'test'@'%'` + `FLUSH PRIVILEGES` | 权限级注入，可逆，不改表结构 |
| 事务回滚 | 发送两个不同 messageId、相同 user/session/sku 的 CREATE_ORDER（触发 `uk_active` 冲突） | 无需恢复（验证回滚语义） | 利用冻结唯一键，不新增约束 |
| RocketMQ 不可用 | `ROCKETMQ.stop()` | `ROCKETMQ.start()` | namesrv+broker 单容器，重启后 Topic 需 `mqadmin updateTopic` 重建 |
| consumer 异常 | 发送非法/不可反序列化消息 | 无需恢复（验证重试与零副作用） | 消费端抛异常由 MQ 重试 |
| 服务异常 | `ServiceLauncher.RunningService.stop()`（关闭对应 Spring 上下文） | 重新 `ServiceSupport.start(...)` 启动新上下文 | 消费组 offset 保留，重启后续跑 |

执行隔离：

- 涉及停容器/停服务的故障类**独立运行**（`mvn -pl integration-test -am test -Dtest=XxxIT`），不与普通集成测试同 JVM 混跑，避免影响共享静态容器；
- 服务上下文启动参数复用 Phase 5.3.2 的 `ServiceSupport`（随机端口、容器地址、Gateway 排除、调度周期调大）；
- 所有异步收敛使用 Awaitility（默认 60s，恢复链路 120s），禁止固定 sleep。

---

## 4. 故障场景设计

### 4.1 Redis 故障

#### R-01 秒杀期间 Redis 不可用

| 要素 | 内容 |
| --- | --- |
| 故障注入方式 | 预热 session/sku/inventory 与 Redis 库存后，执行 `REDIS.stop()`；随后调用 `POST /api/v1/seckill/execute` |
| 影响范围 | seckill-service 的 Redis Lua 预扣、会话缓存、用户防重标记全部不可用；order/inventory/payment 不受直接影响 |
| 预期行为 | execute 快速失败：Redis 连接异常 → 全局异常 → 返回 `10000 SYSTEM_ERROR`（不返回成功、不扣减、不建单、不发消息）；不产生超卖与脏数据 |
| 恢复方式 | `REDIS.start()`；等待端口就绪（Awaitility 探测 PING） |
| 数据一致性检查 | `seckill_pre_deduct` 0 条、`seckill_order` 0 条、`seckill:flow:*` 不存在、inventory 不变（available+locked=total）、Redis 无用户标记 |
| 是否需要补偿 | 否（请求已快速失败，无预扣发生） |

#### R-02 Redis 恢复与库存一致性检查

| 要素 | 内容 |
| --- | --- |
| 故障注入方式 | 承接 R-01：Redis 重启后热点库存键已丢失（容器无持久化），直接调用 execute |
| 影响范围 | 所有依赖 `seckill:stock:*` 的秒杀请求 |
| 预期行为 | 库存键缺失 → Lua 返回 -3 → 映射 `30003 SESSION_NOT_READY`（未预热语义）；不产生订单 |
| 恢复方式 | 测试侧按测试命名空间重新预热 `seckill:stock:{skuId}` / `seckill:stock:total:{skuId}`（等价运维预热动作）；再次 execute 恢复正常 |
| 数据一致性检查 | 预热后 Redis stock = MySQL available = total；调用既有 `GET /api/v1/inventory/admin/reconcile?skuId=` 检查报告无 issue；重新秒杀成功后 DEDUCT 流水 = 1 |
| 是否需要补偿 | 是（Redis 丢失需重新预热；用户防重标记同时丢失，登记为已知风险，不扩展契约） |

#### R-03 Redis 宕机期间发生取消回补

| 要素 | 内容 |
| --- | --- |
| 故障注入方式 | 完成秒杀至 WAIT_PAY + DEDUCT 后 `REDIS.stop()`；触发超时关单（`TimeoutCloseTask.closeExpiredOrders()`）发布 CANCEL_ORDER |
| 影响范围 | inventory 回补消费、seckill 内部回补接口 |
| 预期行为 | MySQL 事实优先：inventory locked 1→0、available 999→1000、RECOVER 流水 1 条；Redis 回补调用失败 → 走冻结的 repair/告警路径（`recoverClient.recover` 返回 false），订单仍为 TIMEOUT |
| 恢复方式 | `REDIS.start()`；测试侧按 RECOVER 流水 requestId 重新执行回补（等价 repair 动作）或验证对账接口标记差异 |
| 数据一致性检查 | MySQL 侧：available=1000、locked=0、RECOVER 流水 1 条；Redis 恢复后 stock=1000；最终 MySQL available = Redis stock = total |
| 是否需要补偿 | 是（Redis 回补失败进入 repair/告警；用户标记清除不在本阶段，见第 6 节边界） |

---

### 4.2 MySQL 故障

#### M-01 数据库连接异常

| 要素 | 内容 |
| --- | --- |
| 故障注入方式 | `MYSQL.stop()`；分别验证：秒杀 execute、order/inventory 消费 |
| 影响范围 | 所有依赖 MySQL 的服务（seckill 商品/场次查询、order 建单、inventory 确认、payment 落库） |
| 预期行为 | 秒杀 execute 快速失败（`10000`），Redis 不扣减；已投递的 CREATE_ORDER 消费抛异常 → RocketMQ 重试，不 ACK；MySQL 恢复前订单/流水不产生 |
| 恢复方式 | `MYSQL.start()`；等待 JDBC 可用（Awaitility 探测 `SELECT 1`）；消费端自动重试成功 |
| 数据一致性检查 | 最终 order=1 条、idempotent=1 条、DEDUCT 流水=1 条、Redis stock=999、无重复业务效果 |
| 是否需要补偿 | 否（RocketMQ 重试天然补偿；幂等保证不重复） |

#### M-02 写入失败

| 要素 | 内容 |
| --- | --- |
| 故障注入方式 | root 连接 `REVOKE INSERT ON seckill_order.* FROM 'test'@'%'` 后投递 CREATE_ORDER |
| 影响范围 | order-service 建单（幂等表/订单/明细插入均失败） |
| 预期行为 | 消费端抛 SQLException → MQ 重试；消息不 ACK、订单不产生；MySQL 无部分数据 |
| 恢复方式 | `GRANT ALL PRIVILEGES ON *.* TO 'test'@'%'` + `FLUSH PRIVILEGES`；等待重试消费成功 |
| 数据一致性检查 | 恢复后 order=1 条、order_item=1 条、idempotent=1 条、active_key 正确；DEDUCT 只 1 次 |
| 是否需要补偿 | 否（MQ 重试 + 幂等） |

#### M-03 事务回滚

| 要素 | 内容 |
| --- | --- |
| 故障注入方式 | 构造两个不同 messageId/orderId、但相同 user/session/sku 的 CREATE_ORDER 消息先后投递，第二条触发 `uk_active` 唯一键冲突 |
| 影响范围 | order-service 建单事务（幂等表 → 订单 → 明细 → 状态流转） |
| 预期行为 | 第一条建单成功；第二条在订单插入处抛 DuplicateKeyException → 事务整体回滚：无 order_item、无幂等记录残留、无状态变更 |
| 恢复方式 | 无需恢复（验证回滚语义；后续按冻结策略告警/对账） |
| 数据一致性检查 | 有效订单 1 条、order_item 1 条、idempotent 仅 1 条（第一条 messageId）；第二条无任何残留；inventory DEDUCT 仅 1 次 |
| 是否需要补偿 | 是（冲突订单进入告警/对账；不新增修复能力） |

---

### 4.3 RocketMQ 故障

#### Q-01 producer 发送失败

| 要素 | 内容 |
| --- | --- |
| 故障注入方式 | 预热库存后 `ROCKETMQ.stop()`；调用 execute（事务消息发送） |
| 影响范围 | seckill-service 事务消息发送 |
| 预期行为 | `RocketMqProducer.sendCreateOrder` 抛异常 → execute 返回 `30006 SECKILL_BUSY`，且已执行的 Redis 预扣被回补（stock 恢复、用户标记清除）、`seckill:flow:{orderId}` 幂等键删除 |
| 恢复方式 | `ROCKETMQ.start()` + `mqadmin updateTopic` 重建 Topic（沿用基类逻辑）；随后 execute 恢复正常 |
| 数据一致性检查 | Redis stock=初始值、用户标记不存在、无 pre_deduct/order/flow key；恢复后单次成功链路各域口径正确 |
| 是否需要补偿 | 是（发送失败已由 seckill-service 内部回补，属冻结行为验证） |

#### Q-02 consumer 异常

| 要素 | 内容 |
| --- | --- |
| 故障注入方式 | 向 `seckill-order-tx:CREATE_ORDER` 发送非法/不可反序列化 payload |
| 影响范围 | order-consumer / inventory-consumer 消费线程 |
| 预期行为 | 消费端解析抛异常 → 消息进入 MQ 重试；不产生任何订单/流水/幂等记录；重试耗尽后按容器默认进入丢弃/告警路径（业务零副作用） |
| 恢复方式 | 无需恢复（验证失败隔离） |
| 数据一致性检查 | order/idempotent/pre_deduct/stock_flow 均为 0；正常消息不受影响 |
| 是否需要补偿 | 否（非法消息不产生业务数据） |

#### Q-03 重试机制

| 要素 | 内容 |
| --- | --- |
| 故障注入方式 | 在 MySQL 权限故障（M-02）或服务停机（S-01/S-02）期间投递 CREATE_ORDER，观察 MQ 重试 |
| 影响范围 | 对应消费组（order-consumer / inventory-consumer / inventory-recover-consumer） |
| 预期行为 | 消费失败不 ACK → RocketMQ 按重试策略延迟重投；故障恢复后消费成功；重试期间无部分业务数据 |
| 恢复方式 | 恢复 MySQL/服务后等待重试窗口（Awaitility 60~120s） |
| 数据一致性检查 | 最终只产生一次业务效果（订单 1、DEDUCT 1、RECOVER 1），无重复 |
| 是否需要补偿 | 否（重试 + 幂等收敛） |

#### Q-04 幂等验证（故障态复验）

| 要素 | 内容 |
| --- | --- |
| 故障注入方式 | 在 MySQL 恢复瞬间重复投递相同 messageId/orderId 的 CREATE_ORDER、相同 requestId 的 CANCEL_ORDER（故障重试与重复消息叠加） |
| 影响范围 | order/inventory 消费端 |
| 预期行为 | 与 Phase 5.3.2 一致：订单/幂等记录/DEDUCT 流水/RECOVER 流水均唯一；Redis 只回补一次 |
| 恢复方式 | 无需恢复 |
| 数据一致性检查 | 各表计数=1、inventory available+locked=total、Redis stock=MySQL available |
| 是否需要补偿 | 否（幂等生效） |

---

### 4.4 服务异常

#### S-01 order-service 异常

| 要素 | 内容 |
| --- | --- |
| 故障注入方式 | 秒杀成功后 `ORDER.stop()`（关闭 order 上下文，消费者下线）；随后投递/已投递的 CREATE_ORDER 无人消费 |
| 影响范围 | 建单消费组 `order-consumer` |
| 预期行为 | 秒杀请求本身成功（Redis 预扣 + 事务消息已发送）；订单暂不创建（消息积压于 MQ）；其他服务（seckill/inventory）不受影响 |
| 恢复方式 | 重新 `ServiceSupport.start(OrderApplication.class, ...)`；消费组 offset 续跑，积压消息被消费 |
| 数据一致性检查 | 恢复前：order=0、Redis stock=999（预扣存在）；恢复后：order=1、WAIT_PAY、pre_deduct=CONFIRMED、无重复订单 |
| 是否需要补偿 | 否（MQ 消息不丢失，重启后续跑） |

#### S-02 inventory-service 异常

| 要素 | 内容 |
| --- | --- |
| 故障注入方式 | 秒杀成功后 `INVENTORY.stop()`；CREATE_ORDER 已投递但 inventory-consumer 下线；同时触发超时关单 |
| 影响范围 | DEDUCT 与 STOCK_RECOVER 消费组 |
| 预期行为 | 订单正常创建（order-service 独立消费组）；inventory 暂不确认（available/locked 不变）；CANCEL_ORDER 积压 |
| 恢复方式 | 重启 inventory 上下文；DEDUCT 与 RECOVER 按顺序/重试消费 |
| 数据一致性检查 | 恢复后：DEDUCT 流水 1、RECOVER 流水按终态 0 或 1；available+locked=total；Redis stock 与 MySQL 一致；无重复扣减/回补 |
| 是否需要补偿 | 否（MQ 续跑）；若出现事实不足走冻结的对账/告警路径 |

#### S-03 payment-service 异常

| 要素 | 内容 |
| --- | --- |
| 故障注入方式 | 支付单创建后 `PAYMENT.stop()`；模拟渠道回调被拒/超时；随后重启 |
| 影响范围 | 支付创建/回调接口、PAY_SUCCESS 发布 |
| 预期行为 | 支付服务不可用期间回调请求失败（连接拒绝）；payment_order 保持 WAIT_PAY；订单不受影响（已知边界：无 PAY_SUCCESS 消费端） |
| 恢复方式 | 重启 payment 上下文；渠道重试回调（相同 transactionNo）→ 防重放 + 状态机正常流转 |
| 数据一致性检查 | 恢复后：payment_order=PAY_SUCCESS、version 2、callback_log 1 条、PAY_SUCCESS 消息 1 条；order 仍为 WAIT_PAY（边界登记，见第 6 节） |
| 是否需要补偿 | 否（渠道重试 + 幂等）；若 PAY_SUCCESS 发送失败走既有“对账兜底”告警 |

---

### 4.5 数据一致性总检查口径

每个故障场景结束后统一校验：

| 数据域 | 口径 |
| --- | --- |
| Redis stock | `seckill:stock:{skuId} = inventory.available_stock`（无锁定时 = total） |
| inventory | `available_stock + locked_stock = total_stock`，无负值；DEDUCT/RECOVER 流水数与业务成功数一致 |
| order 状态 | 合法终态（WAIT_PAY/TIMEOUT/CANCEL/PAY_SUCCESS）与 active_key、cancel_notify_status 语义一致 |
| payment 状态 | 状态机合法（CREATE→WAIT_PAY→PAY_SUCCESS），version CAS 单调 |
| MQ 消息状态 | 业务消息最终被消费（或按冻结策略进 DLQ/告警）；重复投递不产生重复业务效果 |

---

## 5. 测试执行与门禁

1. 演练测试类继承 Phase 5.1 `AbstractIntegrationTest`（复用 Testcontainers），仅使用测试命名空间数据；
2. 涉及停容器的类独立运行，禁止与普通 IT 同 JVM 混跑；
3. 服务启动参数复用 `ServiceSupport`（随机端口、Gateway 排除、调度周期 3600000ms）；
4. 故障恢复全部用 Awaitility 探测（Redis PING、MySQL SELECT 1、RocketMQ 消息可达、订单/流水计数），禁止固定 sleep；
5. 每个场景结束执行一致性校验（4.5 口径）与测试命名空间清理；
6. 演练不改变任何生产代码；如暴露真实缺陷，按 `fix(<module>): xxx` + `test(integration): verify xxx fix` 单独提交。

---

## 6. 已知边界（特别登记）

1. **历史设计边界**：Phase 5.4 编写时 payment 支付成功只验证 payment 状态与 PAY_SUCCESS 消息发布；该消费端已在整体收敛阶段补齐，本报告保留原阶段演练口径；
2. **recover 契约未含 userId**：Redis 回补不校验/不清理 `seckill:user:{skuId}:{userId}` 防重标记；本阶段不回补、不扩展契约，用户标记语义留设计评审；
3. Redis 无持久化：宕机恢复后热点库存与用户标记丢失属预期，由“重新预热 + 对账”恢复；不新增预热接口；
4. RocketMQ 重试次数/延迟以测试容器默认行为为准，不修改消费端配置；
5. 故障演练验证“冻结策略下的行为”，不验证性能指标（Phase 5.5）。

---

## 7. 提交规划

### 7.1 设计阶段（本提交）

```text
docs(test): phase5.4 chaos test design confirmation
```

### 7.2 编码阶段（评审通过后，按域拆分提交）

```text
test(integration): add redis fault drill
test(integration): add mysql fault drill
test(integration): add rocketmq fault drill
test(integration): add service fault drill
```

生产缺陷修复遵循既有规则：`fix(<module>): xxx` 单独提交，随后补 `test(integration): verify xxx fix`，禁止测试与修复混提。

---

## 8. 验收标准

### 成功标准

1. 每个故障场景的预期行为与冻结设计一致（快速失败/重试/幂等/补偿）；
2. 故障恢复后，Redis/MySQL/MQ/订单/支付五域口径满足第 4.5 节；
3. 全程真实中间件注入，无 Mock、无 `Thread.sleep`、无业务代码修改；
4. 所有演练用例通过后全量回归（单元 237 + 集成 17）保持全绿。

### 失败标准

- 故障期间产生超卖、重复订单、重复流水、重复回补；
- 状态机非法跳转被接受；
- 故障恢复后库存口径不一致且无冻结策略兜底；
- 为通过演练修改状态机、接口、数据库设计或扩大业务边界。

---

本设计确认文档提交信息：

`docs(test): phase5.4 chaos test design confirmation`
