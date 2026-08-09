# Phase 6.22 RocketMQ Execution Package（冻结）

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

## Producer / Consumer Config

```
Producer：transaction message，sendMsgTimeout 10s，并发 >= 100
Consumer：clustering 消费，消费组独立
```

## Metrics

```
sendTPS
sendFailure
transactionLatencyP99
consumeTPS
queueDepth
lag
DLQ
```

## Gate

```
failure = 0
DLQ = 0
backlog = 0
```

**状态：包已冻结；生产拓扑未提供，未执行**
