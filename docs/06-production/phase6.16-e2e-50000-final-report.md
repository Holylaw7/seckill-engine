# E2E 50000 Final Report（Phase 6.16 Task 4）

## 执行状态

```
命令：mvn -pl integration-test -am test -Dtest=ProductionScaleValidationTest
      -Dload.enabled=true -Dl08.success-target=50000
结果：NOT PASS（独立环境条件未满足，未执行）
```

## 环境前置（均未满足）

- Independent Load Generator：❌；
- Production RocketMQ：❌；
- Independent Redis：⚠️ 单机 Testcontainers；
- Independent MySQL：⚠️ 单机 Testcontainers。

## 最近一次可记录执行（2026-08-09，5000 档）

```
success: 4999/5000（orders=deduct=4999，分桶 N=8，零超卖口径保持）
oversell=0 / deadlock=0 / inventory_diff=0（该次观测保持）
```

## 失败点 / 根因（明确记录）

```
failure point:   收敛轮询阶段（queryInt 建连）
root cause:      Windows 临时端口耗尽
                 （java.net.BindException: Address already in use: connect）
environment factor: 压测客户端与业务共享单机；HttpURLConnection/短连接未连接复用；
                     Windows 动态端口范围被 TIME_WAIT 占满
code factor:      无业务代码缺陷（订单/DEDUCT 流水一致 4999，零超卖）；
                 测试客户端连接复用改造为登记待办（RealCanaryWindowIT 已验证 JDK HttpClient 复用有效）
```

**E2E 50000 = NOT PASS（独立环境未执行；5000 档受客户端端口耗尽限制）**
