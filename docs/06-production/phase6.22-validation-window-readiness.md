# Phase 6.22 Validation Window Readiness

> 执行窗口定义（资源到位后启用）

## T-24h

```
[ ] environment fingerprint 核验（phase6.20-validation-environment-fingerprint.md 基线）
[ ] dependency scan（GitHub Actions 触发并 GREEN）
[ ] monitoring check（Prometheus / Grafana / Alert）
```

## T-2h

```
[ ] RocketMQ capacity validation（probe total=50000，concurrency=100）
[ ] Redis / MySQL health check（deadlock / lock wait / latency）
```

## T-0 执行顺序

```
1. RocketMQ Production Capacity（Gate：send failure=0 / DLQ=0 / backlog→0）
2. E2E 50000：
   mvn -pl integration-test -am test -Dtest=ProductionScaleValidationTest \
       -Dload.enabled=true -Dl08.success-target=50000
   （success=50000 / oversell=0 / deadlock=0 / inventory_diff=0 /
    duplicate consume safe / recover PASS）
3. Dependency Scan Evidence
4. Production Canary（5→25→50→100%）
5. Operations Sign-off
```

**当前状态：窗口未启用（外部资源 PENDING）**
