# Seckill-Engine seckill-service 设计确认（Phase 4.4）

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.0（评审稿） |
| 状态 | 待评审 |
| 日期 | 2026-08-01 |
| 关联基线 | 需求基线 v1.0 / 架构基线 v1.0 / 详细设计基线 v1.0（Redis/MQ/数据库/接口） |
| 前置依赖 | seckill-common、gateway（限流/鉴权透传）、auth-service（内部风控接口） |

---

## 1. seckill-service 职责边界

| 允许 | 禁止 |
| --- | --- |
| 秒杀场次/资格校验（活动、场次、商品配置） | **禁止直接访问 order_db（seckill_order 库）** |
| Redis 热点库存预热与 Lua 原子预扣 | **禁止直接创建订单**（订单仅由 order-service 消费 `CREATE_ORDER` 创建） |
| 预扣流水（`seckill_pre_deduct`）本地事务写入与回查 | 禁止直接操作 inventory 库（库存事实源/流水归 inventory-service） |
| RocketMQ 事务消息发送（`CREATE_ORDER`） | 禁止直接操作 auth 库与支付库 |
| 秒杀结果查询 | 禁止跨服务直连数据库，数据交换只走 MQ + 内部接口 |
| 调用 auth-service 内部风控接口（`/internal/risk/check`） | 禁止数据库直接扣库存 |

数据归属：只读写 `seckill_seckill` 库（`seckill_activity` / `seckill_session` / `seckill_sku` / `seckill_pre_deduct`），账号 `seckill_rw` 最小权限。

## 2. 秒杀核心链路时序

```text
1 用户请求 → Gateway（限流/鉴权/黑名单，X-User-Id 透传）
2 seckill-service 校验：X-User-Id、场次状态/时间（Redis 缓存 + DB 兜底）、限购参数
3 风控校验：调用 auth-service /internal/risk/check（默认开启，失败 fail-open + 告警）
4 Redis Lua 原子预扣（seckill:stock:{skuId} + seckill:user:{skuId}:{userId}）
   失败（STOCK_EMPTY/REPEAT_BUY/NOT_READY）→ 快速失败返回
5 成功 → 生成 orderId（Snowflake）→ 发送 RocketMQ 半事务消息（CREATE_ORDER）
6 本地事务：插入 seckill_pre_deduct（messageId，状态 INIT）
   → 成功：SUCCESS → Commit 消息
   → 失败：FAIL → Rollback 消息 + Lua 回补库存
7 order-service 消费建单（幂等，本服务不建单）
8 order-service 经内部接口通知本服务回填 pre_deduct（order_id、deduct_status=CONFIRMED）
9 用户查询秒杀结果（轮询 result 接口）
```

| 步骤 | 成功状态 | 失败状态 | 补偿方式 |
| --- | --- | --- | --- |
| 网关层 | 放行 | 限流/黑名单/401 | 无，快速失败 |
| 场次/资格校验 | 通过 | 未开始/已结束/未就绪/参数错误 | 无 |
| 风控校验 | PASS | REJECT/CAPTCHA | 无，按错误码返回 |
| Lua 预扣 | SUCCESS | 库存不足/重复/未预热 | 无（未扣减） |
| 半消息发送 | 发送成功 | 发送失败/超时 | Lua 回补 + BUSY |
| 本地事务 | INIT→SUCCESS | INIT→FAIL | 回补库存 + ROLLBACK |
| 订单创建 | order-service 建单成功 | 消费重试→DLQ | order 侧重试/对账兜底（本服务提供流水查询） |
| 预扣确认 | CONFIRMED | 确认失败 | 对账任务兜底 |

## 3. Redis Lua 脚本设计

### 3.1 预扣脚本（seckill_deduct.lua）

```text
KEYS[1] = seckill:stock:{skuId}
KEYS[2] = seckill:user:{skuId}:{userId}      -- hash tag=skuId，与 KEYS[1] 同槽
ARGV[1] = quantity（默认 1）
ARGV[2] = 购买标记 TTL（秒）

执行：
1 若 KEYS[2] 存在 → 返回 REPEAT_BUY
2 读取 KEYS[1]；不存在 → 返回 NOT_READY（未预热）
3 剩余 < ARGV[1] → 返回 STOCK_EMPTY
4 DECRBY KEYS[1] ARGV[1]
5 SET KEYS[2] 1 EX ARGV[2]
6 返回 SUCCESS
```

返回码：`SUCCESS` / `STOCK_EMPTY` / `REPEAT_BUY` / `NOT_READY`。

### 3.2 回补脚本（seckill_recover.lua，本服务本地补偿用）

```text
KEYS[1] = seckill:stock:{skuId}
KEYS[2] = seckill:stock:total:{skuId}
ARGV[1] = 回补数量

执行：
1 若 KEYS[1] 不存在 → 返回 NOT_READY
2 回补后 > KEYS[2] → 返回 OVER_TOTAL（拒绝并告警）
3 INCRBY KEYS[1] ARGV[1]
4 按场次配置删除购买标记（可选）
5 返回 SUCCESS
```

工程要求：Lua 脚本以资源文件存放（`src/main/resources/lua/`），加载时校验，禁止内联拼接；Cluster 环境下两脚本的 key 均通过 hash tag 保证同槽。

## 4. Key 设计确认

复用冻结 Key 表（Redis 设计 v1.0），本模块**不新增 key**：

| Key | 类型 | TTL | 本服务角色 |
| --- | --- | --- | --- |
| `seckill:session:{sessionId}` | Hash | 场次周期 | 场次状态/时间校验 |
| `seckill:stock:{skuId}` | String | 场次周期 | Lua 原子扣减 |
| `seckill:stock:total:{skuId}` | String | 场次周期 | 回补上限校验 |
| `seckill:user:{skuId}:{userId}` | String | 场次结束+24h | 防重复购买标记 |
| `seckill:lock:preheat:{skuId}` | Redisson Lock | 30s | 预热互斥 |
| `seckill:flow:{orderId}` | String | 24h | 预扣结果幂等标记（防重复发送） |

预热流程（本服务）：场次发布后定时/管理触发 → 校验场次状态 → 从 MySQL 读取投放库存 → 写 total/stock → 校验一致 → 场次置 READY；预热失败禁止开场。

## 5. 秒杀接口设计

| 接口 | 方法/路径 | 说明 |
| --- | --- | --- |
| 发起秒杀 | `POST /api/v1/seckill/execute` | 入参 sessionId/skuId/quantity；同步返回预扣结果 |
| 查询结果 | `GET /api/v1/seckill/result?sessionId=&skuId=` | 轮询：NONE/SUCCESS/FAILED + orderId |
| 场次详情 | `GET /api/v1/seckill/session/{sessionId}` | 读缓存，未命中查 DB |
| 场次列表 | `GET /api/v1/seckill/sessions?activityId=&page=` | 分页 |

`execute` 响应（成功）：

```json
{ "code": 0, "message": "success",
  "data": { "result": "SUCCESS", "orderId": "SO...", "payDeadline": 1782894000000 } }
```

失败走统一错误码：库存不足 `30004`、重复抢购 `30005`、未开始 `30001`、已结束 `30002`、未就绪 `30003`、Redis/MQ 故障 `30006`、风控拒绝 `20003`。

`result` 接口状态来源：Redis 购买标记 → `seckill_pre_deduct` 状态（DEDUCTED/CONFIRMED/RECOVERED）→ order-service 状态（经内部接口查询，预留）。

## 6. RocketMQ Producer 事务消息设计

### 6.1 冻结契约

- Topic：`seckill-order-tx`；Tag：`CREATE_ORDER`；
- 消息字段：`messageId / userId / skuId / sessionId / orderId / timestamp` + 扩展 `quantity / traceId`；
- 消息 Key：`orderId`。

### 6.2 TransactionListener 设计

| 回调 | 行为 |
| --- | --- |
| `executeLocalTransaction` | 插入 `seckill_pre_deduct`（状态 INIT，uk_message_id）→ 成功置 SUCCESS 返回 COMMITTED；唯一冲突返回 COMMITTED；异常置 FAIL 返回 ROLLBACK 并 Lua 回补 |
| `checkLocalTransaction` | 按 messageId 查 `tx_status`：SUCCESS→COMMITTED；FAIL→ROLLBACK；INIT/不存在→UNKNOWN（最多回查 15 次，之后告警人工） |

### 6.3 参数与失败处理

- `sendMsgTimeout=3000ms`、`retryTimesWhenSendFailed=2`；
- 半消息发送失败/超时：本地事务未执行 → Lua 回补 + 返回 `30006`，**禁止重试预扣**；
- 事务消息状态映射（冻结）：DB `INIT/SUCCESS/FAIL` ↔ Broker `UNKNOWN/COMMITTED/ROLLBACK`。

## 7. 预扣流水设计

表：`seckill_pre_deduct`（冻结 DDL，seckill 库）。

| 字段组 | 说明 |
| --- | --- |
| 唯一键 | `message_id`（uk_message_id）防重复消息 |
| `tx_status` | INIT → SUCCESS / FAIL（本地事务状态，供回查） |
| `deduct_status` | DEDUCTED（已预扣）→ CONFIRMED（订单确认）→ RECOVERED（已回补） |
| 回填 | order-service 建单后经内部接口回填 `order_id` 与 `deduct_status=CONFIRMED` |

流水只增不改；与 inventory 库 `stock_flow` 的区别：本表是“秒杀预扣与事务消息”的本地依据，`stock_flow` 是库存事实流水。

## 8. 幂等设计

| 层 | 幂等载体 | 说明 |
| --- | --- | --- |
| 请求层 | Redis 购买标记（Lua 内原子判定） | 同一用户同场次同商品重复请求 → REPEAT_BUY |
| 消息层 | `uk_message_id` | 重复半消息/重复投递不重复插入流水 |
| 发送层 | `seckill:flow:{orderId}` | 防同一预扣结果重复发送 |
| 兜底层 | order 库 `uk_active`（order-service 侧） | 极端场景（Redis 标记丢失）由订单唯一约束兜底 |

消费幂等由 order-service 负责（幂等表 + 唯一索引），本服务仅提供确认回填接口，接口本身幂等（按 messageId/orderId 更新）。

## 9. 异常补偿设计

| 异常 | 检测 | 补偿 | 幂等载体 |
| --- | --- | --- | --- |
| 半消息发送失败/超时 | 发送异常、超时 | Lua 回补 + 快速失败（30006） | 回补校验 ≤ total |
| 本地事务失败 | 插入异常 | 置 FAIL + Lua 回补 + ROLLBACK | deduct_status 幂等更新 |
| 回查异常 | 回查次数 > 15 | 告警 + 人工介入 | message_id 查询 |
| 订单消费失败/丢失 | 对账（pre_deduct 无订单） | order 侧重试/DLQ；本服务提供流水查询接口供对账重投决策 | 唯一约束 |
| Redis 异常 | 客户端异常/熔断 | 快速失败（30006），**禁止 DB 直扣** | - |
| MQ 异常 | 发送/积压指标 | 回补 + 快速失败；积压由消费侧扩容 | - |

补偿铁律：一切回补先校验（≤ total）、留痕（流水/状态）、幂等（唯一约束），禁止无校验直接改 Redis。

## 10. 限流与快速失败策略

### 10.1 多层限流

| 层 | 手段 | 基础值 |
| --- | --- | --- |
| Gateway | 用户+API / IP+API 令牌桶（已实现） | 秒杀路由 replenish 100 / burst 200 |
| 服务内 | Sentinel execute 接口 QPS 限流 | 5,000 QPS（Nacos 可调） |
| 服务内 | Sentinel 热点参数限流（userId/skuId） | 按压测校准 |
| 风控 | auth 内部接口频次校验 | 复用 auth 冻结规则 |

### 10.2 快速失败

未开场/已结束/未就绪/库存不足/重复抢购/限流/风控拒绝/Redis 故障/MQ 故障——全部**立即返回、不排队、不重试**；限流与风控拒绝不可重试，预扣失败不产生消息。

## 11. 数据库表使用范围

```text
可读写（seckill_seckill 库）：
  seckill_activity / seckill_session / seckill_sku / seckill_pre_deduct

禁止访问：
  seckill_order（订单）  ← 禁止直接访问 order_db，禁止创建订单
  seckill_inventory      ← 库存事实源/流水归 inventory-service
  seckill_auth           ← 认证/风控数据归 auth-service
  seckill_payment        ← 支付数据归 payment-service
```

跨服务数据交换：`CREATE_ORDER` 消息（发出）、`/internal/pre-deducts/confirm`（接收 order-service 回填）、`/internal/risk/check`（调用 auth-service，预留 Feign）。

## 12. 单元测试计划

| 测试项 | 内容 | 说明 |
| --- | --- | --- |
| SeckillServiceTest | 场次校验、预扣结果分发（SUCCESS→发消息；失败→快速失败）、补偿回补 | Mockito，全分支 |
| Lua 脚本测试 | 返回码 SUCCESS/STOCK_EMPTY/REPEAT_BUY/NOT_READY、并发扣减 | 真实 Redis 集成（Phase 5），单测先覆盖脚本常量与参数构建 |
| PreDeductServiceTest | 插入幂等（uk_message_id）、状态流转 INIT→SUCCESS/FAIL、确认回填幂等 | Mockito + Mapper mock |
| TransactionListenerTest | executeLocalTransaction 成功/失败/冲突；checkLocalTransaction 三态映射 | Mockito |
| MqProducerTest | 半消息发送、发送失败补偿 | Mock RocketMQTemplate |
| ResultQueryTest | NONE/SUCCESS/FAILED 状态判定 | Mockito |
| 参数校验测试 | execute 参数缺失/格式错误 → 10001 | 统一返回 |

真实 Redis/MQ/MySQL 联调（预热、并发 10,000、事务消息回查）列入 Phase 5 集成与压测。

---

## 附录 A：待评审确认项

1. 风控内部调用默认开启，auth 不可用时 fail-open + 告警（与网关黑名单策略一致）；
2. Sentinel execute 限流基础值 5,000 QPS，压测后经 Nacos 校准；
3. `seckill:flow:{orderId}` 发送幂等标记默认启用；
4. 秒杀结果轮询间隔建议 500ms，由客户端控制。
