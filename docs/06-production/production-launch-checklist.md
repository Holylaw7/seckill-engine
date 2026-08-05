# Phase 6.5 Production Launch Checklist

## Code

- [x] RC 冻结（phase6.5 → release/RC1，version 0.1.0-RC1）
- [x] 工作区 clean（仅遗留 release-check 容错文件）
- [x] 无架构/状态机/幂等/库存模型修改

## Database

- [x] Migration 可升级（V2/V3 幂等可重放）
- [x] Migration 可回滚（ReleaseDrillIT）
- [x] 备份恢复（inventory_bak + 回滚一致性）

## Redis

- [x] Key 预热流程（prepare + prepareBucket）
- [x] key 丢失恢复（BackupRecoveryDrillIT 对账 PASS）
- [ ] 生产 maxmemory/eviction/ACL 确认（运维）

## MQ

- [x] consumer 重启幂等（BackupRecoveryDrillIT）
- [x] 重复消息只生效一次（H-03/F-03 等）
- [ ] DLQ 监控接线（生产）

## Observability

- [x] Gateway/Seckill/Inventory Prometheus 指标（ObservabilitySmokeIT 3/3）
- [x] 告警规则（production-alert-rule.md）
- [ ] Prometheus 抓取 + Grafana 面板接线（生产）
- [ ] RocketMQ Dashboard / 慢 SQL 采集接线（生产）

## Security

- [x] JWT/黑名单/限流语义复核
- [ ] 依赖漏洞扫描（CI dependency-check，CVSS≥7 门禁）
- [ ] 内部接口 mTLS/ACL + repair 管理员鉴权

## Deployment

- [x] Canary 方案（N=1→4→8，CanaryDrillIT 两阶段 PASS）
- [x] 回滚方案（开关 + git revert + DB 兼容）
- [ ] Docker 镜像构建/推送（0.1.0-RC1 标签）

## Final

- [ ] 生产容量独立环境复核（错误率=0 拐点）
- [ ] 发布演练日历（H-05 按类执行）

结论：代码侧全部就绪；**生产网络/鉴权/监控/漏洞扫描**为 Launch 前必须完成的运维项。
