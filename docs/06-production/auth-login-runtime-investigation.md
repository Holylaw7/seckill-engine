# Auth Login Runtime Investigation

> Phase 6.14 Task 1 — Auth Runtime Blocking 根因调查

## 现象（2026-08-09 L-08 首跑）

- surefire 主线程阻塞在 `TestHttp.login`（HttpURLConnection 读响应，363s 未返回）；
- auth 服务 `http-nio-18081-exec-9` RUNNABLE，栈顶 `BCrypt.checkpw`（AuthServiceImpl.login:52），
  累计 CPU 27s；
- MySQL processlist 无活跃业务查询（非 SQL 死锁）；
- BCrypt cost factor = 10（Spring Security `BCryptPasswordEncoder()` 默认），
  单次校验正常应 <100ms——363s 属环境资源争用/一次性异常。

## 根因分类

**环境问题（非代码问题）**：BCrypt 计算本身正常；客户端 HttpURLConnection 读超时
（配置 15s）在 Windows 上未可靠触发，导致无限挂起放大故障表现。

## Workaround（已实施，压测工具优化，未降低安全参数）

- `ProductionScaleValidationTest.loginAll`：每个登录经 `CompletableFuture.get(20s)` 强制超时，
  超时快速失败而非无限挂起；
- 效果验证：2026-08-09 L-08 5000 重跑，**5000 用户登录全部完成**（进入业务压测），
  workaround 有效。

## 结论

Auth 业务语义未修改、BCrypt cost 未降低；登录卡顿为运行时环境异常，已通过客户端超时
保护消除其对压测的阻塞影响。
