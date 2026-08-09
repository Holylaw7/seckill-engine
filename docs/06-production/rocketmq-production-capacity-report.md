# RocketMQ Production Capacity Report

> Phase 6.13 Task 2 — Close MQ Stability Gate

## 状态

```
状态：PENDING（生产规格 RocketMQ 验证未执行）
原因：本环境无生产规格 RocketMQ 拓扑（独立 Namesrv/Broker）；
      Phase 6.13 执行期 Docker daemon 不可用，无法执行任何 MQ 验证。
```

## 已有证据（Phase 6.12 单机探针，非生产规格，仅作根因佐证）

RocketMqCapacityProbeIT（100 并发 / 5000 条事务消息）：

### 2026-08-06（Phase 6.12）

```
environment:   single-host Testcontainers broker（namesrv+broker 单容器）
sent:          3814 / 5000
failed:        1186（23.7%，send timeout）
sendTps:       755.12
transactionP99Ms: 338.25
consumer TPS:  285.97
backlog 收敛:   13,337ms
DLQ count:     0
conclusion:    SINGLE-HOST BROKER SATURATION
```

### 2026-08-09（Docker 恢复后重跑，当前证据）

```
environment:   single-host Testcontainers broker（namesrv+broker 单容器）
sent:          3848 / 5000
failed:        1152（23.0%，send timeout）
sendTps:       907.98
sendP99Ms:     439.11
transactionP99Ms: 312.39
consumer TPS:  272.62
backlog 收敛:   14,115ms
DLQ count:     0
conclusion:    SINGLE-HOST BROKER SATURATION（与 Phase 6.12 一致，稳定复现）
```

数据归档：`docs/06-production/capacity/rocketmq-capacity-probe-2026-08-09.json`

## 生产规格验收条件（未验证）

```
transaction send failure = 0
DLQ = 0
backlog eventually = 0
producer TPS / transaction latency / consumer TPS / backlog curve
```

**MQ Stability Gate = PENDING（生产规格验证未执行；两次单机探针均证实容量瓶颈而非功能缺陷）**
