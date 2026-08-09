# Phase 6.18 E2E 50000 Execution Guide

## 执行入口（已固化）

```bash
mvn -pl integration-test -am test \
  -Dtest=ProductionScaleValidationTest \
  -Dload.enabled=true \
  -Dl08.success-target=50000 \
  -Dtestcontainers.rocketmq.namesrv-port=<port> \
  -Dtestcontainers.rocketmq.broker-port=<port>
```

## Preconditions

- Load Generator 独立（独立机器/容器）；
- RocketMQ production topology（独立 Namesrv + Broker）；
- Redis / MySQL 独立实例；
- 网络可用（broker advertise 地址正确，Phase 6.12 已修复）。

## Success Criteria

```
success = 50000
oversell = 0
deadlock = 0
inventory_diff = 0
MQ backlog eventually = 0
duplicate consume safe
recovery PASS
```

## Failure Classification

| Failure | Category |
| --- | --- |
| MQ timeout | Infrastructure |
| port exhaustion | Load generator |
| inventory diff | Business defect |
| oversell | Critical defect |
| deadlock | Database defect |

执行后必须按此分类记录失败点/根因/环境因素/代码因素。
