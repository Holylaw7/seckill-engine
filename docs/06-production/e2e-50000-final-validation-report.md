# E2E 50000 Final Validation Report

> Phase 6.11 Task 1 — Close BLOCK-03

## 目标

```
50000 / 50000 success
oversell=0 / deadlock=0 / inventory_diff=0
duplicate consume safe / MQ backlog=0 / recovery PASS
```

## 执行拓扑（压测拓扑调整后）

- Gateway / auth / seckill / order / inventory：独立 JVM（各 -Xmx1024m）；
- Redis / MySQL / RocketMQ：独立 Testcontainers 容器（RocketMQ 端口可配置，备用 29876/30911）；
- 压测拓扑：开启 `inventory.sharding.enabled=true, bucket-count=8`（seckill Lua v2 + inventory bucket 扣减）；
- 驱动改进：SustainedLoadExecutor 支持目标达成早停；异常日志降噪；L-08 断言改为分桶口径（SUM(bucket)）。

## Result

```
Target:          50000
Success:         NOT COMPLETED（单机环境）
Oversell:        0（历史各档位保持；未完成最终断言）
Deadlock:        0（历史各档位保持）
MQ:              producer 事务消息发送超时（RemotingTooMuchRequestException），订单 0
Redis/MySQL:     分桶预热一致（8 桶，SUM=STOCK）；未完成最终校验
Recovery:        未执行到抽样阶段
Conclusion:      ENVIRONMENT LIMITED —— NOT PASS
```

## Bottleneck Analysis（诚实记录）

1. **RocketMQ producer 超时**：execute 成功后 `sendMessageInTransaction` 超时
   （`RemotingTooMuchRequestException: sendDefaultImpl call timeout`），消息未达 broker，
   订单无法落库；与分桶无关，属单机 broker/客户端资源饱和；
2. **日志 I/O**：异常路径每请求输出完整堆栈（seckill.log 峰值 739MB），拖垮单机；
   已通过日志降噪参数缓解（`GlobalExceptionHandler=OFF`、`RequestLogGlobalFilter=OFF`）；
3. **Docker 端口缓存**：容器删除后 wslrelay/com.docker.backend 短暂保留端口绑定，
   测试重跑需清理；已通过端口可配置化规避；
4. 单机共享主机资源：Load Generator 与全部服务同机，不满足任务书
   "Load Generator 独立" 要求。

## Final PASS Evidence

**无**（未达成 50000 完整收敛）。保持 NOT PASS，禁止虚报。

## 建议

独立环境（多机 / Load Generator 独立 / 生产规格 RocketMQ 集群）执行
`-Dload.enabled=true -Dl08.success-target=50000`（分桶 N=8 已就绪），
完成后再关闭 BLOCK-03。
