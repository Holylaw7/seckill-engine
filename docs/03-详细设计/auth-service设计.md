# Seckill-Engine auth-service 设计确认（Phase 4.3）

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.1（设计已评审 + 冻结补充） |
| 状态 | 已评审，冻结补充已确认 |
| 日期 | 2026-08-01 |
| 关联基线 | 架构基线 v1.0（12.2 Risk 设计）、详细设计基线 v1.0（数据库/接口/错误码）、Phase 4 开发规范 |
| 前置依赖 | seckill-common（已评审通过）、gateway（已评审通过，JWT 解析入口） |
| 变更记录 | v1.0 评审稿；v1.1 冻结补充：JWT Payload、密钥管理、Session 字段、登录失败策略、管理接口安全 |

---

## 1. 服务职责边界

auth-service 只负责**认证、会话、风控**三个域，归属数据库 `seckill_auth`：

| 域 | 职责 | 数据 |
| --- | --- | --- |
| 认证 | 登录、登出、JWT 签发、用户状态校验 | `user`、`user_auth` |
| 会话 | 登录会话创建/删除/查询 | Redis `auth:session:{userId}` |
| 风控 | RiskService：黑名单、频次、设备指纹（预留）、验证码（预留） | `risk_blacklist`、`risk_record`、Redis `risk:*` |

边界约束：

- 禁止访问订单/库存/支付库，禁止操作秒杀 Redis 库存；
- 风控初期作为 auth-service 内 Risk 模块，接口与实现解耦，未来可抽离 `risk-service`（调用方不变）；
- 不重复实现网关已完成的 JWT 验签解析；auth-service 负责**签发**与用户状态核验。

## 2. 数据库表设计确认

采用详细设计基线（数据库设计.md §4）冻结 DDL，编码阶段落地 `sql/auth-service/V1.0__init.sql`：

| 表 | 关键字段 | 唯一约束 | 说明 |
| --- | --- | --- | --- |
| `user` | id(Snowflake)、username、password_hash、phone、status | `uk_username` | 密码 BCrypt 存储，phone 展示脱敏 |
| `user_auth` | user_id、auth_type(PASSWORD/SMS/THIRD_PARTY)、credential | `uk_user_auth(user_id,auth_type)` | 认证凭据，支持多方式扩展 |
| `risk_blacklist` | biz_type(USER/IP/DEVICE)、biz_value、reason、operator_id、expire_at、status | `uk_blacklist(biz_type,biz_value)` | 黑名单管理事实源 |
| `risk_record` | user_id、ip、device_fingerprint、action、risk_score、decision | 流水只增 | 风控决策留痕 |

设计确认点：

- 主键统一 Snowflake（common 组件生成）；
- 密码使用 BCrypt，禁止明文与 MD5；
- `risk_record` 为流水表，只增不改，按 `(user_id, created_at)` 与 `(ip, created_at)` 建索引。

## 3. JWT 认证流程

### 3.1 登录流程

```text
1 用户提交 username/password（网关白名单放行 /api/v1/auth/login）
2 风控前置：RiskService.check(LOGIN) —— 黑名单/频次校验
3 查询 user + user_auth → BCrypt 校验密码
4 校验用户状态（status=1 正常）
5 创建 Redis 会话（auth:session:{userId}）
6 签发 JWT（HS256，claims：sub=userId、exp=now+2h）
7 返回 {token, userId, expiresAt}
```

### 3.2 与网关的职责切分

| 环节 | 负责方 |
| --- | --- |
| Token 解析 + 验签 + userId 透传（X-User-Id） | gateway（已实现，JwtTokenParser） |
| Token 签发 + 用户状态核验 + 登出失效 | auth-service（本模块） |
| 密钥 | 同一 HMAC secret 配置（`seckill.auth.jwt.secret`，生产经 Nacos 下发，禁止硬编码） |

### 3.3 Token 策略

- access token：2 小时，无 refresh token（基础版，预留扩展）；
- 登出：删除 Redis 会话；token 本身依赖会话失效语义（预留 token 黑名单机制，Phase 6 评估）。

## 4. Session 设计

| Key | 类型 | Value 字段 | TTL | 用途 |
| --- | --- | --- | --- | --- |
| `auth:session:{userId}` | Hash | userId、username、loginAt、ip、deviceFingerprint | 2h | 登录会话与风控上下文 |

规则：

- 登录成功创建，登出删除；
- 会话查询供“当前用户信息”与风控上下文使用；
- TTL 与 token 有效期一致；活跃续期机制预留（Phase 6 评估）；
- 会话失效后，网关侧由 token 过期兜底（401）。

## 5. Risk 模块设计

### 5.1 RiskService 接口（冻结，架构 12.2）

```text
interface RiskService
  check(RiskContext) → RiskResult       // PASS / REJECT / CAPTCHA
  record(RiskContext, RiskResult)       // 风控记录落库
  isBlacklisted(userId/ip/device) → boolean
  verifyCaptcha(challenge, answer)      // 预留扩展
```

RiskContext：`{userId, ip, deviceFingerprint, action(LOGIN/SECKILL/PAY), path, timestamp}`  
RiskResult：`{decision, reason, riskScore}`

### 5.2 实现链（按序短路）

1. 黑名单校验（Redis `risk:blacklist:*`）→ REJECT；
2. 频次校验（Redis `risk:rate:login:user:{userId}` / `risk:rate:login:ip:{ip}`，60s 窗口，超限 → CAPTCHA/REJECT）；
3. 设备指纹（预留采集，写入 `risk_record`）；
4. 验证码扩展接口（预留）。

### 5.3 演进

RiskService 以接口暴露，实现类可整体迁移至独立 `risk-service`，业务调用方（Feign 契约）不变。

## 6. 黑名单管理流程

### 6.1 拉黑

```text
管理员/风控触发 → 写 MySQL risk_blacklist（原因/操作人/过期时间，uk 幂等）
  → 同步 Redis：risk:blacklist:user:{userId} / ip:{ip} / device:{device}
  → TTL = expire_at 或长期（无过期时间）
  → 操作审计
```

### 6.2 解封

```text
管理员操作 → MySQL status 置失效（保留历史）→ 删除 Redis key → 审计
```

### 6.3 一致性

- MySQL 为黑名单管理事实源，Redis 为网关/风控查询热点；
- Redis 同步失败由补偿任务兜底（Phase 5 对账覆盖），网关侧 Redis 异常 fail-open（已实现）。

## 7. Gateway 交互方式

| 交互 | 方式 | 说明 |
| --- | --- | --- |
| 登录/登出/会话 | 同步 REST（网关路由 `/api/v1/auth/**`） | 网关白名单仅放行 `/api/v1/auth/login` |
| 风控校验 | 内部接口 `POST /api/v1/auth/internal/risk/check` | 供 seckill-service 等通过 Feign 调用（`/internal` 前缀仅内网/服务间） |
| Header 透传 | `X-Trace-Id`、`X-User-Id` | 网关生成/解析后透传，auth-service 直接读取 |
| 认证协同 | 网关验签解析入口，auth-service 签发与状态核验 | 不重复验签 |

## 8. API 接口列表

| 接口 | 方法/路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| 登录 | `POST /api/v1/auth/login` | 放行 | 入参 username/password；出参 token/userId/expiresAt |
| 登出 | `POST /api/v1/auth/logout` | token | 删除会话 |
| 当前会话 | `GET /api/v1/auth/session` | token | 返回用户与会话信息 |
| 风控校验 | `POST /api/v1/auth/internal/risk/check` | 内部 | 出参 decision/reason |
| 拉黑 | `POST /api/v1/auth/admin/blacklist` | 管理员 | 入参 bizType/bizValue/reason/expireAt |
| 解封 | `DELETE /api/v1/auth/admin/blacklist` | 管理员 | 按 bizType/bizValue |
| 黑名单列表 | `GET /api/v1/auth/admin/blacklist?page=` | 管理员 | 分页 |
| 风控记录 | `GET /api/v1/auth/admin/risk-records?page=` | 管理员 | 预留 |

统一使用 `Result<T>` 返回；管理员接口通过 `X-User-Role`（预留）或管理端鉴权。

## 9. 异常码映射

不新增错误码，复用冻结 ErrorCode（message 可覆盖）：

| 场景 | code | message（可覆盖） |
| --- | --- | --- |
| 用户名/密码错误 | 20001 | “用户名或密码错误” |
| 账号被禁用 | 20002 | “账号已被禁用” |
| 未登录/token 失效 | 20001 | 默认文案 |
| 无权限（非管理员） | 20002 | 默认文案 |
| 风控拒绝（黑名单/异常行为） | 20003 | 按原因 |
| 触发验证码 | 20004 | “需要人机验证” |
| 黑名单拦截 | 20005 | “账号已被限制” |
| 参数错误 | 10001 | 默认文案 |
| 系统异常 | 10000 | 默认文案 |

如需新增专用错误码（如“验证码错误”），按错误码变更评审流程先行申请，编码阶段不私自新增。

## 10. 单元测试计划

| 测试项 | 内容 | 通过标准 |
| --- | --- | --- |
| JwtTokenIssuerTest | 签发格式、claims 一致性、过期时间、与 gateway 解析规则互通（HS256 三段式） | 双向可解析 |
| AuthServiceTest（Mockito） | 登录成功、密码错误、账号禁用、风控拦截、登出删会话 | 分支全覆盖 |
| RiskServiceTest | 黑名单命中、频次超限（CAPTCHA/REJECT）、白名单放行、设备指纹记录 | 决策正确、记录落库 |
| BlacklistServiceTest | 拉黑/解封、Redis 同步、uk 幂等 | 幂等不重复 |
| SessionServiceTest | 创建/查询/删除、TTL 设置 | 行为正确 |
| 参数校验测试 | login 参数缺失/格式错误 → 10001 | 统一返回 |

数据库真实联调（MyBatis Plus Mapper + MySQL）列入 Phase 5 集成测试（Testcontainers），本阶段以 Mockito 单测为主。

---

## 附录 A：待评审确认项

1. 登录频次规则：默认用户 5 次/分钟、IP 20 次/分钟，超限触发验证码（基础版直接拒绝）；
2. access token 时效 2h，无 refresh token（预留）；
3. 账号禁用复用 20002 覆盖文案，不新增错误码；
4. 设备指纹仅预留字段与记录能力，采集实现 Phase 6 评估；
5. JWT 密钥开发环境默认值仅本地使用，生产 Nacos 下发。

---

## 附录 B：设计冻结补充（评审确认，v1.1）

### B.1 JWT Payload 字段冻结

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sub` | String | 用户 ID |
| `username` | String | 登录名 |
| `iat` | Long | 签发时间（秒） |
| `exp` | Long | 过期时间（秒） |
| `jti` | String | Token 唯一 ID（UUID），用于未来 Token 撤销与审计 |
| `roles` | String | 角色（逗号分隔，如 `USER` / `USER,ADMIN`） |

`jti` 仅用于审计与预留撤销，基础版不维护撤销列表（会话删除为准）。

### B.2 JWT 密钥管理冻结

- 开发环境：`application.yml` 配置 `seckill.auth.jwt.secret`（仅本地默认值）；
- 测试/生产：Nacos 配置中心下发（key 结构不变），**禁止代码硬编码 secret**；
- gateway 与 auth-service 使用同一 secret（生产同源下发）。

### B.3 Session 字段冻结

`auth:session:{userId}` Hash 字段：

| 字段 | 说明 |
| --- | --- |
| `userId` | 用户 ID |
| `username` | 登录名 |
| `tokenVersion` | Token 版本（初始 1，用于未来版本化失效） |
| `loginTime` | 登录时间（毫秒） |
| `lastActiveTime` | 最后活跃时间（毫秒） |
| `device` | 设备指纹（预留） |

TTL 与 access token 一致（2h）。

### B.4 登录失败策略冻结

- 连续失败计数：`risk:login:fail:{userId}`（用户不存在时以 username 作为标识），窗口 10 分钟；
- 失败 ≥ 5 次：触发验证码（`CAPTCHA_REQUIRED` 20004）；
- 失败 ≥ 10 次：临时冻结（`RISK_REJECTED` 20003，提示“账号已临时冻结”）；
- 登录成功：清除失败计数。

### B.5 管理接口安全冻结

- 拉黑、解封、黑名单查询（`/api/v1/auth/admin/**`）必须 **JWT 有效 + roles 含 ADMIN** 双重校验；
- 实现：auth-service 内 `AdminAuthInterceptor`（拦截器）二次解析 Authorization 头校验角色，不依赖网关改造；
- roles 来源：`user.roles` 列（默认 `USER`，逗号分隔），`user` 表 DDL 同步新增该列。
