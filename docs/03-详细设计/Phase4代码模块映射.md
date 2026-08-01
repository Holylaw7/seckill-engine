# Seckill-Engine Phase 4 代码模块映射（冻结）

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.0（冻结） |
| 状态 | 已评审冻结 |
| 日期 | 2026-08-01 |

## 模块职责边界

| 模块 | 核心职责 | 关键设计点 | 主要依赖 |
| --- | --- | --- | --- |
| gateway | 限流、认证、路由 | 令牌桶+漏桶、JWT 校验、黑名单、traceId 透传 | Redis、auth-service |
| auth-service | 登录认证、会话、风控 | RiskService（防刷/黑名单/设备指纹预留/验证码扩展） | Redis、MySQL(auth) |
| seckill-service | 秒杀入口、Lua 调用、MQ Producer | 场次校验、Lua 预扣、事务消息、结果查询 | Redis、RocketMQ、MySQL(seckill) |
| inventory-service | 库存事实管理、流水、补偿 | inventory/stock_flow、STOCK_RECOVER 消费、修复任务 | Redis、RocketMQ、MySQL(inventory) |
| order-service | 订单创建、订单状态机、幂等 | CREATE_ORDER 消费、状态机、超时关单、幂等表 | RocketMQ、MySQL(order) |
| payment-service | 支付状态 | 支付单/流水、回调验签、查单、退款 | MySQL(payment)、外部渠道 |
| common | DTO、异常、Snowflake、工具类 | 统一返回、全局异常、错误码、ID 生成 | - |
| monitor | 指标、健康检查 | Prometheus 指标、告警入口（Phase 7 完善） | 各服务 |

## 冻结约束

- 禁止跨服务直接访问数据库（见数据库设计 11.4）；
- 禁止业务逻辑写进 Controller（Controller 只做参数校验与协议转换）；
- Service 负责业务与事务边界，DAO 只做数据访问；
- 模块间契约使用 `seckill-api`（DTO/VO/Feign 接口）。

## 开发顺序（冻结）

`common → gateway → auth-service → seckill-service → inventory-service → order-service → payment-service`

monitor 与部署相关内容在 Phase 7 实现。
