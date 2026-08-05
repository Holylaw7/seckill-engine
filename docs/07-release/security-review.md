# Phase 6.5 RC-08 Security Review

## 1. JWT

| 检查项 | 结果 |
| --- | --- |
| 允许缓存（Mac 实例 / SecretKey） | ✅ ThreadLocal<Mac> + SecretKeySpec 缓存 |
| 禁止缓存 claims/权限/秒杀资格 | ✅ 未缓存（代码复核） |
| 验签 + 过期校验 | ✅ 保持（constant-time 比较 + exp 校验） |
| 黑名单实时校验 | ✅ MGET 每请求执行 |

## 2. Redis

- 仅存储库存计数/防重标记/黑名单标记，无敏感个人信息；✅
- 建议：生产 Redis 启用 ACL + 网络隔离。

## 3. API

| 接口 | 现状 | 风险 | 建议 |
| --- | --- | --- | --- |
| POST /api/v1/seckill/execute | 网关 JWT + 参数校验 | 低 | — |
| POST /api/v1/seckill/internal/pre-deducts/confirm | 无应用内鉴权（内网直连） | 中 | mTLS/ACL + 内网隔离 |
| POST /api/v1/seckill/internal/stocks/recover | 无应用内鉴权（requestId 幂等） | 中 | mTLS/ACL + 内网隔离 |
| POST /api/v1/inventory/admin/reconcile/repair | 无管理员鉴权（注释 Phase 7 完善） | 高 | 上线前必须接入管理员鉴权/ACL |

## 4. 结论

- JWT/Redis/参数校验符合冻结语义；✅
- 内部接口与 repair 接口鉴权为**上线前置条件**：生产环境必须内网隔离 + mTLS/ACL，repair 接口接入管理员鉴权（登记 Phase 7）。
- Security Review 完成；上述前置条件在 Production Launch 前必须满足。
