# Seckill-Engine payment-service 设计确认（Phase 4.7 + 整体收敛补充）

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.1（评审稿 + PAY_SUCCESS 已实施） |
| 状态 | 已评审，支付回调到订单状态闭环已实现 |
| 日期 | 2026-08-17 |
| 关联基线 | 需求基线 v1.0 / 架构基线 v1.0 / 详细设计基线 v1.0（数据库/MQ/接口/事务边界） |
| 前置依赖 | seckill-common、order-service（订单金额快照与状态机已支持 WAIT_PAY→PAY_SUCCESS） |

---

## 1. 服务职责边界

payment-service 负责：支付单创建、支付状态管理、支付渠道抽象、支付回调处理、回调验签、支付查询、退款申请、支付对账。

| 允许 | 禁止 |
| --- | --- |
| 读写 `seckill_payment` 库（payment_order / payment_callback_log / payment_refund） | **禁止访问 order_db / inventory_db / seckill_db / auth_db** |
| 调用支付渠道（Mock/微信/支付宝预留） | **禁止直接操作 Redis**（nonce 防重放走 DB 唯一约束） |
| 发布 `PAY_SUCCESS` 事件（MQ） | **禁止直接修改订单状态**（订单更新由 order-service 消费 PAY_SUCCESS 完成） |
| 内部 HTTP 接口（order-service 服务端调用创建支付） | 禁止直接修改库存 |
| 支付对账与退款补偿任务 | 禁止跨服务直连数据库 |

跨服务通信：MQ（发布 `PAY_SUCCESS`）+ 内部 HTTP 接口（order-service → payment-service 创建支付）。
order-service 已通过独立消费组消费 `PAY_SUCCESS`，不直接修改 payment 数据库。

## 2. 数据库设计确认

独立 database：`seckill_payment`（账号 `payment_rw` 最小权限）。

### 2.1 payment_order（冻结字段）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT | 主键（Snowflake） |
| payment_no | VARCHAR(64) | 支付单号，`uk_payment_no` |
| order_no | VARCHAR(64) | 订单号 |
| user_id | BIGINT | 用户 ID |
| amount | DECIMAL(18,2) | 支付金额（服务端传递，客户端不可信） |
| channel | VARCHAR(20) | 渠道：MOCK/WECHAT/ALIPAY |
| status | VARCHAR(20) | CREATE/WAIT_PAY/PAY_SUCCESS/REFUNDING/REFUND_SUCCESS/PAY_FAILED |
| transaction_no | VARCHAR(64) | 渠道交易号，`uk_transaction_no` |
| active_order_key | VARCHAR(64) NULL | **order_no + active payment 唯一**：有效支付占用 order_no，终态置 NULL |
| pay_time / refund_time | DATETIME(3) | 支付/退款时间 |
| version | INT | 乐观锁 CAS |
| created_time / updated_time | DATETIME(3) | 按冻结字段命名（与全局 created_at 命名差异见附录 B） |

### 2.2 payment_callback_log（冻结字段）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT | 主键（Snowflake） |
| payment_no | VARCHAR(64) | 支付单号 |
| channel_transaction_no | VARCHAR(64) | 渠道交易号，`uk_callback_transaction`（防重放） |
| callback_body | TEXT | 回调原文快照 |
| verify_result | VARCHAR(20) | VERIFY_OK / VERIFY_FAIL / TIMEOUT / AMOUNT_MISMATCH |
| process_status | VARCHAR(20) | NEW/PROCESSING/SUCCESS/SKIPPED_DUPLICATE/FAILED |
| trace_id | VARCHAR(64) | 全链路追踪 |
| created_time | DATETIME(3) | 创建时间 |

### 2.3 payment_refund（补充表，待评审确认）

退款唯一性 `payment_no + refund_no` 的落点：`payment_refund`（refund_no 唯一、payment_no 索引、status REFUNDING/REFUND_SUCCESS/REFUND_FAILED、channel_refund_no、version）。

## 3. 支付状态机（冻结）

```text
CREATE → WAIT_PAY → PAY_SUCCESS → REFUNDING → REFUND_SUCCESS
                 └→ PAY_FAILED（异常状态）
```

| 当前状态 | 事件 | 目标状态 |
| --- | --- | --- |
| CREATE | 创建完成 | WAIT_PAY |
| WAIT_PAY | 支付成功回调 | PAY_SUCCESS |
| WAIT_PAY | 支付失败/超时 | PAY_FAILED |
| PAY_SUCCESS | 发起退款 | REFUNDING |
| REFUNDING | 渠道退款成功 | REFUND_SUCCESS |

规则（冻结）：

- 所有状态变更必须经过 `PaymentStateMachine`（状态前置检查 + `version` CAS），**禁止 Controller 直接修改 status**；
- 禁止非法跳转（如 PAY_SUCCESS→PAY_FAILED、REFUNDING→PAY_SUCCESS）；
- 重复事件幂等，不改变终态。

## 4. 支付创建流程

```text
order-service（服务端，已鉴权）
  → POST /api/v1/payments/create（内部/服务端调用）
  → 入参：orderNo、userId、amount（订单金额快照）、channel
  → 幂等：uk_payment_no（重复 requestId 返回原单）/ active_order_key（同订单有效支付唯一）
  → 生成 payment_no（Snowflake）
  → 保存 payment_order（CREATE → WAIT_PAY，version CAS）
  → 调用渠道 createPay() 获取支付参数
  → 返回 {paymentNo, channelParams}
```

要求：

- 幂等创建：同一请求幂等键返回首次结果；
- 同订单不可重复有效支付：`active_order_key`（可空唯一列，order_no 占用，终态释放）；
- 客户端不传金额；金额由 order-service 服务端传递，payment-service 只做记录与校验（>0、与回调金额比对）。

## 5. 支付回调设计

```text
支付渠道 → POST /api/v1/payments/callback/{channel}（网关白名单已放行）
  1 验签（渠道密钥 HMAC/RSA，Mock 用 HMAC）
  2 时间窗口校验（±5 分钟）
  3 callback_log 落库（uk_callback_transaction 防重放：重复回调直接返回成功）
  4 金额校验：回调金额 == payment_order.amount（不符拒绝 + 告警）
  5 payment_order CAS 更新 WAIT_PAY → PAY_SUCCESS（幂等）
  6 发布 PAY_SUCCESS 事件（MQ）
  7 order-service 消费更新订单 WAIT_PAY → PAY_SUCCESS（已实施，按 paymentNo 幂等）
```

回调要求：验签、幂等、防重复、防篡改（原文快照 + 验签结果留痕）；**重复回调直接返回成功**。

## 6. MQ 设计（PAY_SUCCESS 事件）

| 项 | 内容 |
| --- | --- |
| Topic / Tag | `seckill-order-tx` / `PAY_SUCCESS` |
| 消息字段 | messageId、paymentNo、orderNo、userId、amount、transactionNo、timestamp |
| 发送方 | payment-service（支付成功后） |
| 消费方 | order-service（消费组 `order-pay-success-consumer`） |
| 消费动作 | 订单 WAIT_PAY → PAY_SUCCESS，写入 `paid_at` |

消费幂等：order-service 幂等表（`PAY_SUCCESS/paymentNo`）+ 订单状态机 CAS；重复事件无副作用。

## 7. 退款设计

```text
用户/管理员退款申请（payment_no + refund_no）
  → 状态机 PAY_SUCCESS → REFUNDING（version CAS）
  → 记录 payment_refund（refund_no 唯一）
  → 渠道 refund()
  → 成功：REFUNDING → REFUND_SUCCESS，写 refund_time
  → 失败：REFUND_FAILED，进入补偿任务（周期扫描重试，幂等 refund_no）
```

- 退款请求唯一：`payment_no + refund_no`（payment_refund 唯一约束）；
- 补偿任务：失败/超时退款按 refund_no 重试（有限次），仍失败告警人工；
- 退款成功后订单 REFUND 状态由 order-service 消费退款事件处理（后续阶段，预留）。

## 8. 支付渠道抽象

```java
interface PaymentChannel {
    PayResult createPay(PayRequest request);          // 创建支付，返回渠道参数
    boolean verifyCallback(CallbackContext context);  // 验签 + 时间窗口
    PayStatus query(PaymentQueryRequest request);     // 查单
    RefundResult refund(RefundRequest request);       // 退款
}
```

- 实现：`MockPaymentChannel`（本阶段，HMAC 验签、可配置成功/失败）；
- 预留：`WeChatPaymentChannel`、`AlipayPaymentChannel`（Phase 7 接入真实渠道）；
- 渠道按 `channel` 字段路由（简单工厂）。

## 9. 安全设计

| 项 | 设计 |
| --- | --- |
| 回调签名验证 | 渠道密钥验签（Mock 用 HMAC-SHA256），验签失败拒绝并记 log |
| 时间窗口 | 回调时间与本地时间差 > 5 分钟拒绝 |
| nonce 防重放 | `uk_callback_transaction` 唯一约束（不依赖 Redis）；重复回调直接返回成功 |
| IP 白名单预留 | 配置项 `payment.callback.ip-whitelist`（默认空=不启用，Phase 7 启用） |
| 金额校验 | 回调金额必须等于支付单金额，不符拒绝 + 告警；创建接口金额由服务端传递，**禁止相信客户端金额** |
| 越权 | 支付查询按 user_id 归属校验 |

## 10. 测试计划

| 测试项 | 覆盖 |
| --- | --- |
| PaymentServiceTest | 创建支付、重复创建幂等、同订单重复支付拒绝、查询 |
| PaymentStateMachineTest | 冻结流转矩阵、CAS 成功/冲突、非法跳转 |
| CallbackHandlerTest | 验签成功、验签失败、重复回调幂等、时间窗口超限、金额不符、回调落库 |
| PaymentChannelTest | MockChannel createPay/verify/query/refund、可配置失败 |
| RefundServiceTest | 退款成功、退款失败补偿重试、refund_no 幂等 |
| MqProducerTest | PAY_SUCCESS 消息字段与发送失败处理 |
| 集成 | `PaymentCallbackFlowIT` 覆盖首次回调、重复回调、金额异常、验签异常和订单状态闭环 |

---

## 附录 A：跨服务契约变更申请（已实施）

支付成功后订单状态更新已由 order-service 新增消费实现：

| 项 | 内容 |
| --- | --- |
| 变更 | order-service 消费 `seckill-order-tx/PAY_SUCCESS`，幂等表（`PAY_SUCCESS/paymentNo`）+ 状态机更新 WAIT_PAY→PAY_SUCCESS |
| 影响 | order-service 状态机 `canTransition` 已支持，状态机 CAS 成功时写入 `paid_at` |
| 实施 | 已实施并通过 `PaySuccessConsumerTest` 与 `PaymentCallbackFlowIT` |

## 附录 B：待评审确认项

1. 字段命名：`payment_order` 按冻结清单使用 `created_time/updated_time`（与全局 `created_at/updated_at` 不一致，确认是否统一）；
2. `payment_refund` 补充表（退款唯一性落点）确认；
3. `active_order_key` 可空唯一列实现「order_no + active payment」唯一；
4. 退款成功后订单 REFUND 流转由后续阶段接入（预留）。
