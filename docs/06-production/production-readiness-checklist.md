# 生产发布就绪检查清单（Phase 6.4）

## Code

- [ ] 分支 clean（`git status` 无未提交业务代码）
- [ ] 版本冻结（tag/version 一致，无 SNAPSHOT 依赖进入生产）
- [ ] 禁止项复核：未修改状态机 / 库存一致性模型 / Redis Lua 扣减语义 / 幂等要求 / 未关闭校验

## Database

- [ ] Migration 已验证（V1~V3 可重复执行，含 restore 重放）
- [ ] 分桶迁移 dry-run 通过，`sum(bucket.total)=inventory.total`
- [ ] 回滚已验证（新表可弃、旧表不动、N=1 等效旧系统）

## Redis

- [ ] Key 预热（`seckill:stock:*`、`seckill:stock:total:*`、分桶 key）
- [ ] 黑名单 fail-open 已验证（H-05 F-02）
- [ ] Redis 故障恢复（预热 + 对账 REPAIR）演练通过

## MQ

- [ ] 重试策略验证（非法消息零副作用，Q-03）
- [ ] 重复消费幂等验证（CREATE_ORDER / CANCEL_ORDER，H-05 F-03）
- [ ] backlog=0 收敛验证（L-07 / L-03）

## Monitoring（上线必须存在）

- [ ] QPS（Gateway / seckill / consumer）
- [ ] RT（p50/p95/p99）
- [ ] error rate / 429 rate
- [ ] MQ lag / 消费 TPS
- [ ] inventory 行锁等待 / deadlock
- [ ] 对账差异（Redis vs MySQL）

## Deployment

- [ ] Canary 方案（Gateway 独立 JVM、N=1→4→8 分桶灰度）
- [ ] 回滚方案（配置开关 `inventory.sharding.enabled=false`、独立 commit revert）
- [ ] 发布演练（H-05 F-01 实例下线流量迁移）
