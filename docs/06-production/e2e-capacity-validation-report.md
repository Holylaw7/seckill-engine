# E2E Capacity Validation Report（L-08）

> Phase 6.10 Task 3 — High Volume E2E Capacity Validation（BLOCK-03）
> 测试资产：ProductionScaleValidationTest（@load，-Dl08.success-target 可配）

## Target

```
50000 success target（必须执行档）
100000（建议档，protocol ready，未执行）
```

## 执行环境

隔离生产拓扑（Gateway/auth/seckill/order/inventory 独立 JVM + Testcontainers 中间件），
单机 Windows 主机；压测客户端与全部服务共享主机资源。

## Result

```
Target:         50000
Success:        49989 / 50000（99.98%；最后一次 DB 观测 2026-08-06 14:17:54）
Oversell:       0（available + locked = total 保持；历史各档位均为 0）
Deadlock:       0（此前所有档位均为 0；本次因收敛窗口中断未完成最终确认）
MQ:             backlog 尾部 ~11 单未在收敛窗口内追平（消费尾部极慢，单机环境瓶颈）
Redis:          Redis global 口径与 MySQL 一致（历史一致性校验 PASS；本次未完成最终校验）
Recovery:       未执行到 CANCEL RECOVER 抽样（收敛断言未满足，测试未到达该阶段）
Conclusion:     ENVIRONMENT LIMITED —— 未达成完整 50000 PASS，禁止虚报
```

## 卡点说明（诚实记录）

- 负载阶段 90min 窗口内成功数达到目标（execute 侧），进入 MQ 收敛等待；
- order/inventory 消费在单机 + 单 SKU 行锁（L-08 走 legacy 非分桶路径）下尾部收敛速率
  降至每分钟个位数，DB 订单/DEDUCT 流水停在 49989；
- 收敛窗口（30min）内未追平，测试被终止（进程停止），不构成 50000 完整 PASS。

## 结论

**E2E 50000 = NOT PASS（environment limited）**。GA 门禁中 E2E 50k 项保持未关闭；
须在独立环境（多机/分桶 N=8 开启）完成 50000 完整收敛后再放行 GA。
