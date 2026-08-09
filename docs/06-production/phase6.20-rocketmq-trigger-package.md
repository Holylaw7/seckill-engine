# Phase 6.20 RocketMQ Trigger Package（冻结）

## Topology

```
Producer
  |
Namesrv Cluster
  |
Broker Cluster
  |
Consumer
```

禁止：single broker / single container / development topology。

## Trigger

```bash
mvn -pl integration-test -am test \
  -Dtest=RocketMqCapacityProbeIT \
  -Dload.enabled=true \
  -Drocketmq.probe.total=50000 \
  -Drocketmq.probe.concurrency=100
```

输出：`docs/06-production/capacity/rocketmq-production-validation.json`
（sendCount/successCount/failureCount/sendTPS/transactionLatencyP99/
disk usage/commit latency/queue depth/consumeTPS/lag/DLQ）。

## Gate

```
send failure = 0
DLQ = 0
backlog eventually = 0
```
