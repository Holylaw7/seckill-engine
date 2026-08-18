# Phase 6.24 风险收敛报告

> 日期：2026-08-18
> 分支：`release/RC1`
> 范围：不改变秒杀、库存分桶、Redis Lua 或既有接口语义，关闭已识别的分布式运行风险。

## 1. 交付结论

| 风险 | 当前实现 | 状态 |
| --- | --- | --- |
| RocketMQ 单 NameServer/单 Broker | 新增双 NameServer + `SYNC_MASTER/SLAVE` 生产 Compose 和持久卷 | 工程实现完成，跨节点容量/故障切换待外部环境 |
| `REFUND_SUCCESS` 未闭环 | payment 提交后发布，order 按 `refundNo` 幂等更新 `REFUND` | 已关闭 |
| nonce 单实例内存防重放 | 两个内部接口过滤器改用 Redis `SET NX EX`，TTL 60 秒 | 已关闭 |
| Docker 默认密钥进入生产 | 生产 Compose 使用必填变量，新增 PowerShell/Bash 密钥校验 | 工程防误用完成，实际密钥托管由部署平台负责 |

## 2. 退款事件时序

```text
支付退款本地事务
  ├─ payment_order: REFUNDING → REFUND_SUCCESS
  ├─ payment_refund: REFUND_SUCCESS + PENDING
  └─ 事务提交
       └─ 发布 REFUND_SUCCESS
            ├─ 成功：条件更新 order_notify_status=SENT
            └─ 失败：保持 PENDING，补偿任务重试
```

订单服务只接受 `PAY_SUCCESS → REFUND`，重复消息使用
`idempotent(biz_type=REFUND_SUCCESS, biz_id=refundNo)` 幂等。
退款事件不会在 payment 本地事务回滚前发送。

## 3. 生产启动约束

```bash
# 由 Vault/KMS/云密钥管理系统预先注入变量
./scripts/validate-production-env.sh
docker compose -f docker/docker-compose.production.yml config --quiet
docker compose -f docker/docker-compose.production.yml up -d --build
```

PowerShell 使用 `scripts/validate-production-env.ps1`。脚本不会打印密钥，
会拒绝缺失值、已知演示值和长度不足的服务密钥。

## 4. 验证范围

- 单元测试：退款服务提交后发布、通知失败保留 `PENDING`、订单退款幂等、Redis nonce 共享防重放；
- Compose 静态校验：演示拓扑和生产拓扑均须通过 `docker compose ... config --quiet`；
- 生产 MQ 的真实吞吐、跨节点故障切换、DLQ 收敛和跨可用区 RTO 仍需独立资源验证；
- 同一 Docker 主机上的主从容器不构成物理故障域隔离，不作为生产 HA 结论。

## 5. 剩余风险

`Dependency Scan`、E2E 50000、生产 Canary、RocketMQ 跨节点容量和运营签核仍属于
外部环境门禁，不由本地单测或 Compose 静态校验替代。
