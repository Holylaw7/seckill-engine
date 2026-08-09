# Phase 6.18 RocketMQ Validation Plan

## Topology（要求）

```
Producer
   |
Namesrv Cluster
   |
Broker Cluster
   |
Consumer
```

禁止：single container broker（Phase 6.14 已实测单机双容器亦不达标）。

## Metrics

Producer:

```
send TPS
transaction latency
send failure count
retry count
```

Broker:

```
disk usage
commit latency
queue depth
```

Consumer:

```
consume TPS
lag
DLQ
```

## Gate

```
send failure = 0
DLQ = 0
backlog eventually = 0
```

## 执行资产（已就绪）

- RocketMqCapacityProbeIT（探针，支持事务消息压测）；
- RocketMqIndependentTopology（独立 namesrv/broker 容器资产）；
- 探针参数：`-Drocketmq.probe.total` / `-Drocketmq.probe.concurrency`。

生产规格执行时：`count >= 50000`、`concurrency >= 100`，输出
`rocketmq-production-capacity-final.json`。
