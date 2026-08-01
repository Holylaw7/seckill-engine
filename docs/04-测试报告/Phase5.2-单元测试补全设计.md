# Phase 5.2 单元测试补全设计确认文档

版本：v1.0（待评审冻结）
分支：feature/phase5-test
基线：Phase 5.1（commit 5cb53b5）

---

## 1. 当前测试覆盖分析

Phase 5.1 结束时全工程 202 个单元测试全部通过，覆盖分布如下：

| 模块 | 测试类数 | 测试方法数 | 主要覆盖点 |
|---|---:|---:|---|
| seckill-common | 8 | 28 | Result/PageResult、ErrorCode 唯一性与分段、BusinessException、GlobalExceptionHandler、Snowflake 百万级/并发/workerId 隔离/时钟回拨、TraceId 线程隔离、JsonUtils 泛型 |
| gateway | 7 | 22 | JWT 正常/过期/篡改/错密钥/畸形、白名单放行、黑名单用户与 Redis 异常 fail-open、限流组合 Key、TraceId 生成与透传、参数校验、异常响应无堆栈 |
| auth-service | 7 | 34 | 登录成功链（风控→BCrypt→Session→JWT）、密码错误/阈值/冻结/禁用/黑名单、RiskService check/record/blacklist/captcha、Session 创建/查询/删除/TTL/touch、管理员角色拦截 |
| seckill-service | 7 | 37 | Lua 返回码映射与 null→NOT_READY、库存准备/回补/用户标记、TransactionListener COMMIT/ROLLBACK/UNKNOWN/幂等/回补、PreDeduct 状态流转与已回补拒绝、秒杀全分支（未开始/已结束/库存不足/重复购买/风控拒绝/发送失败回补） |
| inventory-service | 6 | 27 | CAS 冲突重试、流水 uk_biz 幂等与插入冲突 fallback、CREATE_ORDER 正常/业务错误/runtime 重试、STOCK_RECOVER 正常/超时映射/客户端失败不中断、对账一致/异常检测/REPAIR 流水 |
| order-service | 7 | 26 | 状态机矩阵/CAS/非法跳转、建单与重复建单、取消释放 active_key、错误 owner/非 WAIT_PAY 取消、CANCEL_ORDER 发布与补偿任务、超时关单正常/冲突跳过 |
| payment-service | 7 | 28 | 回调正常/验签失败/时间窗口/重复 transaction/金额不一致/已成功不重发、状态机矩阵/CAS/非法跳转、支付创建幂等/插入冲突 fallback、退款成功/幂等/渠道失败/补偿重试 |
| **合计** | **49** | **202** | — |

结论：冻结测试计划的骨架已基本建立，Phase 5.2 的目标是补齐边界断言与显式场景，而不是重建测试体系。

---

## 2. 缺口列表

对照冻结测试计划逐项审计后的真实缺口：

| 编号 | 模块 | 缺口 | 现状 |
|---|---|---|---|
| G-01 | common | GlobalExceptionHandler 仅覆盖 BusinessException 与未知异常，缺 `MethodArgumentNotValidException`、`BindException` | 部分覆盖 |
| G-02 | common | PageResult 缺 JSON 序列化字段完整性与泛型反序列化 | 缺失 |
| G-03 | common | Result 缺泛型嵌套反序列化验证（现有仅序列化） | 部分覆盖 |
| G-04 | common | ErrorCode 分段断言仅抽查 5 个代表码，未遍历全量枚举 | 可增强 |
| G-05 | gateway | JWT 缺“payload 缺少必要字段（sub/roles）”拒绝路径 | 缺失 |
| G-06 | gateway | JWT 缺 roles 权限校验（非 ADMIN 访问管理类路由） | 缺失 |
| G-07 | gateway | 黑名单缺 IP 维度显式测试（现有用户维度 + fail-open） | 缺失 |
| G-08 | gateway | 限流 Key 缺秒杀接口 `/api/v1/seckill/**` 专项断言 | 缺失 |
| G-09 | auth | JWT Payload 缺字段完整性断言（sub/username/iat/exp/jti/roles 全量存在） | 可增强 |
| G-10 | auth | Session tokenVersion 失效语义（版本不一致视为无效会话）显式验证 | 缺失 |
| G-11 | seckill | Lua 脚本资源缺静态语义测试（脚本存在、包含 check/decr/set 关键命令、返回码注释与实现一致） | 缺失 |
| G-12 | seckill | RedisStockService 缺 NOT_READY/REPEAT_BUY 显式分支断言（现有经映射测试间接覆盖） | 可增强 |
| G-13 | inventory | CAS 超过最大重试次数后进入异常/失败路径 | 缺失 |
| G-14 | inventory | CREATE_ORDER 消费端显式验证“库存不足→业务错误吞掉（不重试）”场景 | 缺失 |
| G-15 | inventory | STOCK_RECOVER 消费端重复消息 requestId 幂等（不重复回补、不重复调客户端） | 缺失 |
| G-16 | order | active_key 在 PAY_SUCCESS 后保留的显式断言 | 缺失 |
| G-17 | order | TimeoutCloseTask 重复执行幂等（已关闭订单不再处理/不再发布） | 缺失 |
| G-18 | order | 状态机补强：CANCEL/TIMEOUT 后支付成功被拒、重复流转被拒 | 可增强 |
| G-19 | payment | 状态机补强：重复支付、重复退款、越级流转显式断言 | 可增强 |
| G-20 | payment | CallbackHandler 已成功回调重复投递不重复发 PAY_SUCCESS（现有已成功不重发覆盖，可补断言细化） | 可增强 |

明确的边界（不在 Phase 5.2 单元测试内做）：

- **Lua 并发原子性**（10000 并发/零超卖/仅一人成功）：依赖真实 Redis 单线程语义，属于 Phase 5.3 Testcontainers 集成测试与 Phase 5.5 并发压测范围。单元测试只保证脚本资源、参数与返回码映射正确，避免用 Mock Redis 伪造并发语义。
- 跨服务链路（Gateway→Auth→Seckill→MQ→Order→Inventory→Payment）：Phase 5.3 集成测试范围。

---

## 3. 测试新增文件规划

原则上在**现有测试类内追加方法**，仅对新增职责新建测试类：

| 模块 | 新增/扩展文件 | 类型 |
|---|---|---|
| seckill-common | `GlobalExceptionHandlerTest`（扩展 2 方法）、`PageResultTest`（扩展 2 方法）、`ResultTest`（扩展 1 方法）、`ErrorCodeTest`（扩展 1 方法） | 扩展 |
| gateway | `JwtTokenParserTest`（+payload 缺失）、`JwtAuthGlobalFilterTest`（+roles 校验）、`BlacklistGlobalFilterTest`（+IP 黑名单）、`RateLimitConfigTest`（+秒杀 key） | 扩展 |
| auth-service | `JwtTokenIssuerTest`（+payload 完整性）、`SessionServiceTest`（+tokenVersion 失效） | 扩展 |
| seckill-service | 新建 `LuaScriptTest`（脚本资源静态语义）；`RedisStockServiceTest`（+NOT_READY/REPEAT_BUY 显式分支） | 新建 1 + 扩展 |
| inventory-service | `InventoryServiceImplTest`（+CAS 超限）、`CreateOrderConsumerTest`（+库存不足）、`StockRecoverConsumerTest`（+requestId 幂等） | 扩展 |
| order-service | `OrderServiceImplTest`（+PAY_SUCCESS 保留 active_key）、`TimeoutCloseTaskTest`（+重复执行幂等）、`OrderStateMachineTest`（+CANCEL/TIMEOUT 后支付被拒、重复流转） | 扩展 |
| payment-service | `PaymentStateMachineTest`（+重复支付/重复退款/越级）、`CallbackHandlerTest`（+重复回调不重发细化） | 扩展 |

不新增生产代码、不新增测试依赖（JUnit 5 / Mockito / AssertJ 已由 spring-boot-starter-test 提供）。

---

## 4. 每个模块测试目标

### 4.1 seckill-common（目标 +5~6）

- 参数校验异常与 Bind 异常统一映射为 Result（无堆栈泄漏）；
- PageResult 序列化包含 list/total/pageNum/pageSize，泛型可反序列化；
- Result 泛型嵌套（如 `Result<PageResult<OrderVO>>`）往返一致；
- ErrorCode 全量枚举遍历：唯一 + 分段 10000/20000/30000/40000/50000 + message 非空。

### 4.2 gateway（目标 +4）

- JWT payload 缺少 sub/roles 时拒绝；
- 管理路由携带非 ADMIN roles 返回 403；
- IP 黑名单命中拒绝、未命中放行；
- 秒杀接口限流 Key 使用 `/api/v1/seckill/**` 规则与 user+api 组合。

### 4.3 auth-service（目标 +2~3）

- 签发的 JWT 包含全部冻结字段（sub/username/iat/exp/jti/roles）；
- tokenVersion 与 Session 不一致时视为无效会话。

### 4.4 seckill-service（目标 +3）

- Lua 脚本资源加载成功，脚本内容包含 check stock / check user / decr / set flag 关键命令，返回码注释与 Java 侧映射一致；
- RedisStockService 显式验证 NOT_READY、REPEAT_BUY 分支。

### 4.5 inventory-service（目标 +3）

- CAS 冲突超过最大重试次数后抛错/进入异常路径（不静默吞掉）；
- CREATE_ORDER 消费端库存不足走业务错误吞掉（不触发 MQ 重试）；
- STOCK_RECOVER 重复消息按 requestId 幂等，客户端只调用一次。

### 4.6 order-service（目标 +4）

- PAY_SUCCESS 状态保留 active_key（不释放）；
- TimeoutCloseTask 重复执行对已关闭订单幂等；
- 状态机补强：CANCEL/TIMEOUT 后 PAY_SUCCESS 被拒、重复流转被拒。

### 4.7 payment-service（目标 +3）

- 状态机补强：PAY_SUCCESS 后重复支付被拒、重复退款被拒、越级流转被拒；
- 已成功回调重复投递不重复发布 PAY_SUCCESS。

---

## 5. Mock 策略

- 统一使用 Mockito mock 外部边界（RedisTemplate/Redisson、RocketMQTemplate、Mapper、RestTemplate/FeignClient、Clock）；
- 状态机与幂等逻辑使用内存态/真实本地对象验证，不启动 Spring 容器；
- 禁止 `Thread.sleep`、随机数据、依赖真实外部服务；
- 测试命名统一 `xxx_should_yyy_when_zzz`；结构统一 Arrange/Act/Assert；
- Lua 并发原子性、跨服务链路等真实语义验证明确移交 Phase 5.3 集成测试（Testcontainers），Phase 5.2 不做假并发验证。

---

## 6. 是否需要修改测试基础设施

**不需要。** test-support（Testcontainers 公共基类、application-test.yml、sql/test-data）已满足 Phase 5.2 单元测试需求；Phase 5.3 将直接复用该基础设施。

---

## 7. 预计新增测试数量

| 模块 | 预计新增 |
|---|---:|
| seckill-common | 5~6 |
| gateway | 4 |
| auth-service | 2~3 |
| seckill-service | 3 |
| inventory-service | 3 |
| order-service | 4 |
| payment-service | 3 |
| **合计** | **24~26** |

Phase 5.2 结束后预计全工程单元测试 **226~228** 个。

---

## 8. Commit 规划

严格按冻结顺序逐模块提交，禁止一次性修改所有模块：

| 顺序 | 模块 | Commit 类型 | 建议信息 |
|---|---|---|---|
| 1 | seckill-common | `test(common)` | `test(common): complete common boundary test coverage` |
| 2 | gateway | `test(gateway)` | `test(gateway): complete jwt blacklist ratelimit coverage` |
| 3 | auth-service | `test(auth)` | `test(auth): complete jwt payload and session version coverage` |
| 4 | seckill-service | `test(seckill)` | `test(seckill): add lua script semantic tests` |
| 5 | inventory-service | `test(inventory)` | `test(inventory): complete cas and consumer idempotency coverage` |
| 6 | order-service | `test(order)` | `test(order): complete active key and timeout task coverage` |
| 7 | payment-service | `test(payment)` | `test(payment): complete state machine and callback coverage` |

规则：

- 每个模块提交前执行 `git status` 确认改动范围仅限该模块测试；
- 每个模块提交前执行 `mvn -pl <module> test` 且全绿；
- 如测试暴露真实生产缺陷：以 `fix(module): xxx` 单独提交，并附缺陷说明；
- 禁止修改业务流程、删除约束、降低一致性要求、跨模块重构；
- 全部完成后执行全量 `mvn test` 输出最终测试统计。

---

本设计确认文档提交：

`docs(test): phase5.2 unit test design confirmation`
