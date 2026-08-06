# E2E 50000 Independent Validation Report

> Phase 6.12 Task 1 — Close BLOCK-03

## Environment（强制项预检）

| 组件 | Phase 6.12 实际环境 | 任务书要求 | 满足 |
| --- | --- | --- | --- |
| Load Generator | 与业务服务共享单机 Windows（Maven/surefire 进程） | 独立机器/容器 | ❌ |
| Gateway | 独立 JVM | 独立 JVM | ✅ |
| Business Services | 独立 JVM × 4 | 独立 JVM | ✅ |
| Redis | Testcontainers 独立容器 | 独立实例 | ✅ |
| MySQL | Testcontainers 独立容器 | 独立实例 | ✅ |
| RocketMQ | namesrv+broker 单容器（单机） | 生产规格拓扑（独立 Namesrv/Broker） | ❌ |
| CPU / Memory | 20 逻辑核 / 32GB（全部组件共享） | 独立资源 | ❌ |

**结论：环境不满足任务书强制要求，本环境不具备执行独立 50000 验证的资格。**

## Execution

Phase 6.11/6.12 实测记录（作为根因证据，不替代独立验证）：

```
command: mvn -pl integration-test -am test -Dtest=ProductionScaleValidationTest
         -Dload.enabled=true -Dl08.success-target=50000（分桶 N=8）
Phase 6.11: producer 事务消息超时，订单 0（端口通告不匹配 + 单机 broker 饱和）
Phase 6.12 根因修复: broker 通告端口随映射可配置（测试基础设施缺陷，已修复）
Phase 6.12 探针: 单机 broker 100 并发事务消息 → sent=3814/5000，failed=1186（23.7%）
```

## Result

```
success >= 50000:  NOT VERIFIED（环境不满足，未在独立环境执行）
oversell=0:        历史档位证据保持（L-07/CanaryExpansion 10000）
deadlock=0:        历史档位证据保持
inventory_diff=0:  历史一致性证据保持（RedisStockValidationIT）
MQ backlog=0:      单机探针收敛 13.3s（5000 档）；50000 档未完成
duplicate safe:    历史证据保持（L-07/FullRollback Drill）
recovery PASS:     历史证据保持（L-07/FullRollback Drill）
```

**BLOCK-03 = NOT PASS（独立环境未执行，禁止虚报）**

## 独立环境执行指引（资产已就绪）

```
mvn -pl integration-test -am test \
  -Dtest=ProductionScaleValidationTest -Dload.enabled=true \
  -Dl08.success-target=50000 \
  -Dtestcontainers.rocketmq.namesrv-port=19876 \
  -Dtestcontainers.rocketmq.broker-port=20911
```

要求：Load Generator 独立机器/容器；RocketMQ 独立 namesrv + broker（生产规格）；
完成后以本报告模板补充 Environment/Execution/Result 后关闭 BLOCK-03。
