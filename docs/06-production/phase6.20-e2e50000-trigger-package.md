# Phase 6.20 E2E 50000 Trigger Package（冻结）

## Command

```bash
mvn -pl integration-test -am test \
  -Dtest=ProductionScaleValidationTest \
  -Dload.enabled=true \
  -Dl08.success-target=50000 \
  -Dtestcontainers.rocketmq.namesrv-port=<port> \
  -Dtestcontainers.rocketmq.broker-port=<port>
```

## Environment Variables

```
JAVA_HOME：目标环境 JDK21
testcontainers.rocketmq.namesrv-port / broker-port：独立环境映射端口
```

## Expected Metrics

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
| MQ timeout | MQ infrastructure |
| port exhaustion | Load generator |
| oversell | Critical business defect |
| inventory diff | Inventory defect |
| deadlock | Database defect |
| duplicate order | Order consistency defect |
