# Phase 6.5 RC-03 Observability Checklist

> 状态：Gateway / Inventory 指标已实现并经 ObservabilitySmokeIT 验证；MQ/Redis/JVM 按生产监控方案登记。

## 1. Gateway Metrics（已实现 + 验证）

| 指标 | 类型 | 来源 | 状态 |
| --- | --- | --- | --- |
| `gateway_request_total` | Counter | RequestLogGlobalFilter | ✅ 已暴露 |
| `gateway_request_duration` | Timer | RequestLogGlobalFilter | ✅ 已暴露 |
| `gateway_error_total` | Counter（status≥500） | RequestLogGlobalFilter | ✅ 已实现 |
| `gateway_429_total` | Counter（status=429） | RequestLogGlobalFilter | ✅ 已实现 |

## 2. Inventory Metrics（已实现 + 验证）

| 指标 | 类型 | 来源 | 状态 |
| --- | --- | --- | --- |
| `inventory_deduct_success_total` | Counter | InventoryServiceImpl | ✅ 已暴露 |
| `inventory_deduct_fail_total` | Counter（BusinessException） | InventoryServiceImpl | ✅ 已实现 |
| `inventory_deduct_duration_seconds` | Timer | InventoryServiceImpl | ✅ 已暴露 |
| `inventory_recover_total` | Counter | InventoryServiceImpl | ✅ 已实现 |
| `inventory_repair_total` | Counter | ReconciliationService（登记） | 待生产接线 |
| `inventory_bucket_lock_wait` | 登记 | MySQL performance_schema（row lock waits） | 监控侧采集 |

## 3. MQ Metrics（监控侧登记）

| 指标 | 来源 | 状态 |
| --- | --- | --- |
| `mq_consumer_lag` | RocketMQ Dashboard / mqadmin consumerProgress | 生产接线 |
| `mq_consume_success` | 消费组 TPS 监控 | 生产接线 |
| `mq_consume_retry` | broker 重试队列监控 | 生产接线 |
| `mq_dlq_count` | %DLQ% 队列消息数 | 生产接线 |

## 4. Redis 监控

- `redis_command_latency`：Redis INFO + 客户端 Timer（gateway/inventory 压测已采样）；
- `redis_error_total`：客户端异常计数（登记）。

## 5. JVM

- GC log：已开启（隔离拓扑与生产服务 `-Xlog:gc`）；
- heap / thread：Micrometer JVM 指标（`jvm_memory_used_bytes`、`jvm_threads_live_threads`）随 actuator 暴露（冒烟已见 jvm_* 指标）。

## 6. 暴露端点（生产）

- `GET /actuator/prometheus`（gateway、inventory 已配置）；
- `GET /actuator/health`（用于 LB 探活）；
- 建议生产：management 端点仅内网暴露 + 独立监控账号。

## 7. 验证

- `ObservabilitySmokeIT`：gateway_request_total/duration、inventory_deduct_success_total/duration 真实存在（2/2 PASS）。
