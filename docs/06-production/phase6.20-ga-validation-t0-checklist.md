# Phase 6.20 GA Validation T-0 Checklist

> 资源到位后、执行前逐项确认；任一未勾选 → 不启动 T-0

## Before Execution

```
[ ] Independent Load Generator（独立机器/容器）
[ ] Production MQ（独立 Namesrv Cluster + Broker Cluster）
[ ] Independent Redis / MySQL
[ ] CI GREEN（GitHub Actions dependency-scan 通过）
[ ] Monitoring enabled（Prometheus / Grafana / Alert）
[ ] Rollback ready（RTO<5min 预案 + 责任人）
[ ] Owners assigned（Release / SRE / DB / Rollback）
[ ] 环境指纹与目标环境一致（phase6.20-validation-environment-fingerprint.md 基线）
```

## 启动顺序

```
1. 环境指纹核验
2. RocketMQ 生产容量验证（MQ Gate）
3. E2E 50000（BLOCK-03）
4. Dependency Scan CI 证据（BLOCK-01）
5. Production Canary 5→25→50→100%（BLOCK-02）
6. Operations Sign-off（BLOCK-04）
7. GA Decision
```
