# Phase 6.5.5 Observability Hardening

> 状态：Gateway / Inventory / Seckill 指标已实现并经 ObservabilitySmokeIT 验证（3/3 PASS）。

## 1. Metrics

### Gateway

| 指标 | 类型 | 状态 |
| --- | --- | --- |
| gateway_request_total | Counter | ✅ |
| gateway_latency（gateway_request_duration） | Timer | ✅ |
| gateway_429_total | Counter | ✅ |
| gateway_error_total | Counter | ✅ |

### Seckill

| 指标 | 类型 | 状态 |
| --- | --- | --- |
| seckill_success_total | Counter | ✅ |
| seckill_fail_total | Counter | ✅ |
| seckill_stock_empty_total | Counter | ✅ |

### Inventory

| 指标 | 类型 | 状态 |
| --- | --- | --- |
| inventory_deduct_success_total | Counter | ✅ |
| inventory_deduct_fail_total | Counter | ✅ |
| inventory_bucket_lock_wait_seconds | Timer | ✅ |
| inventory_deadlock_total | Counter（Deadlock/CannotAcquireLock） | ✅ |
| inventory_recover_total | Counter | ✅ |
| reconcile_diff_total | Counter（对账差异条数） | ✅ |

### MQ（监控侧接线）

- mq_consumer_lag / mq_retry_total / mq_dead_letter_total：RocketMQ Dashboard + mqadmin，生产接线。

## 2. 日志要求（关键链路字段）

| 字段 | 现状 |
| --- | --- |
| traceId | 网关/服务请求日志已含；业务异常日志补全中 |
| orderId | 建单/扣减/恢复日志已含 |
| skuId | 扣减/恢复/对账日志已含 |
| bucketNo | 分桶扣减/恢复日志已含（Phase 6.2 起） |
| messageId | 消费链路日志已含 |
| 敏感信息 | 禁止打印密码/Token/支付敏感字段（代码复核通过） |

## 3. 暴露端点

- gateway / seckill / inventory：`/actuator/prometheus`、`/actuator/health` 已配置；
- 生产：management 端点仅内网 + 独立监控账号。

## 4. 验证

- ObservabilitySmokeIT 3/3：gateway_request_total/duration、inventory_deduct_success_total/duration、seckill_success_total 均真实存在。
