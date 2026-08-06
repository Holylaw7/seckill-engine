# RocketMQ Capacity Validation

> Phase 6.12 Task 2 — BLOCK-03 supporting evidence

## 根因链（Phase 6.11 → 6.12）

1. **测试基础设施缺陷（已修复）**：RocketMQ 容器 broker 通告端口硬编码 20911；
   使用备用映射端口（30911）时 producer 拿到的 broker 地址 127.0.0.1:20911 在宿主机不存在，
   导致 `sendMessageInTransaction` 超时、订单 0。已修复：broker 通告端口随映射端口可配置
   （`testcontainers.rocketmq.broker-port`）。
2. **单机 broker 饱和（实测确认）**：连接修复后，探针 100 并发事务消息仍有 23.7% 发送失败。

## 实测数据（RocketMqCapacityProbeIT / rocketmq-capacity-probe.json）

```
environment:   single-host Testcontainers broker（namesrv+broker 单容器）
total:         5000（事务消息）
concurrency:   100
sent:          3814
failed:        1186（23.7%，send timeout）
sendTps:       755.12
sendP99Ms:     502.93
transactionP99Ms: 338.25
consumer TPS:  285.97
backlog 收敛:   13,337ms
DLQ count:     0
conclusion:    SINGLE-HOST BROKER SATURATION
```

## 生产规格验证要求（未执行）

生产规格 RocketMQ（独立 namesrv + broker 集群）下需确认：

```
producer TPS / transaction latency / send failure count
consumer TPS / backlog curve / DLQ count
```

**结论：单机 broker 容量不足已被实测证实；生产规格拓扑验证 = PENDING（需独立环境）。**
