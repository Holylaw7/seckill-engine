# Seckill-Engine 操作手册（Runbook）

> 版本：v1.0（演示部署 + 测试 + 运维 + 回滚）
> 适用：个人项目本地/Docker 部署使用

---

## 1. 系统组件与端口

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| Gateway | 8080 | 唯一入口（JWT/限流/黑名单/Canary） |
| auth-service | 8081 | 登录、JWT |
| seckill-service | 8082 | 秒杀执行（Redis Lua） |
| order-service | 8083 | 建单、取消、超时关闭 |
| payment-service | 8084 | 支付创建/回调/退款（MOCK 渠道） |
| inventory-service | 8085 | 库存扣减/恢复/对账 |
| MySQL | 3306 | 业务库（5 个 schema） |
| Redis | 6379 | 库存/防重/限流 |
| RocketMQ namesrv / broker | 9876/9877 / 10911/10921 | 演示单节点；生产双 NameServer + SYNC_MASTER/SLAVE |

---

## 2. 本地测试（无需 Docker 可跑单测）

```bash
export JAVA_HOME=<JDK21 路径>
mvn clean test                                    # 单元 + 默认集成套件
mvn -pl integration-test -am test                 # 真实中间件集成（需 Docker）
```

常用定向测试：

```bash
# 集成环境冒烟（MySQL/Redis/RocketMQ 连通）
mvn -pl integration-test -am test -Dtest=IntegrationEnvironmentSmokeIT

# 安全（内部接口 ACL / Repair 权限）
mvn -pl integration-test -am test -Dtest=InternalApiSecurityIT,RepairAuthorizationIT

# 全链路（登录→秒杀→建单→扣减→支付）
mvn -pl integration-test -am test -Dtest=SeckillFullFlowIT
```

---

## 3. Docker 一键部署（演示环境）

```bash
# 可选：先复制并修改环境变量（PowerShell 可用 Copy-Item .env.example .env）
cp .env.example .env
docker compose -f docker/docker-compose.yml up -d --build
docker compose -f docker/docker-compose.yml ps          # 查看状态
docker compose -f docker/docker-compose.yml logs -f     # 查看日志
docker compose -f docker/docker-compose.yml down        # 停止（保留数据卷）
docker compose -f docker/docker-compose.yml down -v     # 停止并清空数据
```

首次构建约 5-15 分钟（拉依赖/镜像）；MySQL 首次启动执行 `sql/` 初始化脚本（含演示种子）。
Compose 会等待 MySQL、Redis、RocketMQ namesrv/broker 的健康检查通过后再启动业务服务。
MySQL、Redis 和 RocketMQ broker 数据分别保存在命名卷中；删除卷前必须确认已完成备份。

默认环境变量见仓库根目录 `.env.example`。默认值仅服务于本地演示，至少应替换：
`MYSQL_ROOT_PASSWORD`、`SECKILL_JWT_SECRET`、`ORDER_SERVICE_SECRET`、
`INVENTORY_SERVICE_SECRET`、`INTERNAL_ADMIN_SECRET` 和 `CANARY_CONTROL_TOKEN`。

### 演示初始化（首次启动后执行一次）

MySQL 种子仅含场次/SKU/库存汇总，**分桶行与演示用户需手动准备**：

```bash
# 1) 插入演示用户（密码 Test@123，hash 为 BCrypt 示例值）
docker exec seckill-mysql-compose mysql -uroot -pseckill-root \
  -e 'INSERT INTO seckill_auth.`user` (id, username, password_hash, status, roles)
      VALUES (10001, "tester", "$2a$10$wIDa2z5fwwlfxInfI0rVEOWwsp3damWoubNwXRUyOUAmdlcZ/Io36", 1, "USER");'

# 2) 插入 8 个分桶行（SKU 20001，每桶 125）
docker exec seckill-mysql-compose mysql -uroot -pseckill-root \
  -e 'INSERT INTO seckill_inventory.inventory_bucket
      (id, sku_id, bucket_no, total_stock, locked_stock, available_stock, version)
      VALUES (200011,20001,0,125,0,125,0),...,(200018,20001,7,125,0,125,0);'

# 3) Redis 预热
for i in $(seq 0 7); do docker exec seckill-redis-compose redis-cli SET seckill:stock:bucket:20001:$i 125; done
docker exec seckill-redis-compose redis-cli SET seckill:stock:20001 1000
docker exec seckill-redis-compose redis-cli SET seckill:stock:total:20001 1000
```

> 说明：生产环境应由分桶迁移服务（InventoryBucketMigrationService）自动执行，
> 演示环境因容器内无迁移入口而手动准备。

---

## 4. 关键配置说明

### 库存分桶（默认演示开启）

```yaml
inventory:
  sharding:
    enabled: true      # 生产目标；false 等价旧单行模型
    bucket-count: 8
```

### Canary 灰度（Gateway）

```yaml
seckill:
  gateway:
    canary:
      enabled: true
      weight: 5         # 0-100
      version: RC1
      control-enabled: true
      control-token: dev-canary-token
```

动态调整权重：

```bash
curl -X POST http://localhost:8080/actuator/canary \
  -H "Content-Type: application/json" \
  -H "X-Canary-Token: dev-canary-token" \
  -d '{"weight":25}'
curl http://localhost:8080/actuator/canary   # 查看当前权重
```

### 内部接口签名（服务间）

```yaml
seckill:
  internal-auth:
    enabled: true
    clients:
      order-service: dev-order-secret
      inventory-service: dev-inventory-secret
    admin-secret: dev-admin-secret
```

> 生产必须替换所有演示密钥/口令（Gateway JWT、internal-auth、MySQL root）。
> nonce 防重放使用 Redis `SET NX EX`（60 秒）；Redis 不可用时内部请求 fail-closed。

### 生产拓扑与密钥

生产部署使用独立文件，不使用演示 Compose 的默认值：

```bash
# 先由 Vault/KMS/云密钥管理系统注入以下环境变量
./scripts/validate-production-env.sh
docker compose -f docker/docker-compose.production.yml config --quiet
docker compose -f docker/docker-compose.production.yml up -d --build
```

生产 Compose 包含两个 NameServer 和同一 `broker-a` 的同步主从 Broker。
同一台物理机上的容器只能验证配置与基础复制，不能替代跨节点/跨可用区部署。

---

## 5. 业务使用（演示流程）

### 登录

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"tester","password":"Test@123"}'
```

### 秒杀

```bash
curl -X POST http://localhost:8080/api/v1/seckill/execute \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -H "X-User-Id: 10001" \
  -d '{"sessionId":30001,"skuId":20001,"quantity":1}'
```

### 创建支付（MOCK）

```bash
curl -X POST http://localhost:8080/api/v1/payments/create \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -d '{"orderNo":"<ORDER_NO>","userId":10001,"amount":99.00,"channel":"MOCK"}'
```

### 取消订单 / 库存回补

```bash
curl -X POST http://localhost:8080/api/v1/orders/<ORDER_NO>/cancel \
  -H "Authorization: Bearer <TOKEN>"
```

### 退款成功闭环

退款单成功后，payment-service 在本地事务提交后发布 `REFUND_SUCCESS`；
order-service 校验订单归属和金额后按 `refundNo` 幂等执行 `PAY_SUCCESS → REFUND`。
发送失败或进程在提交后退出时，`payment_refund.order_notify_status=PENDING`，
由退款补偿任务重试，不需要人工重复调用退款接口。

---

## 6. 压测命令（Load 资产，默认跳过）

```bash
# Gateway 容量（G-09）
mvn -pl integration-test -am test -Dtest=GatewayProductionCapacityBenchmark \
    -Dload.enabled=true -Dgateway.perf.duration-seconds=30 -Dgateway.perf.levels=100,200

# E2E 容量（L-07 10000）
mvn -pl integration-test -am test -Dtest=EndToEndProductionCapacityTest \
    -Dload.enabled=true -Dl07.success-target=10000

# E2E 50000（需独立 Load Generator + 生产 MQ）
mvn -pl integration-test -am test -Dtest=ProductionScaleValidationTest \
    -Dload.enabled=true -Dl08.success-target=50000

# RocketMQ 容量探针
mvn -pl integration-test -am test -Dtest=RocketMqCapacityProbeIT \
    -Dload.enabled=true -Drocketmq.probe.total=5000 -Drocketmq.probe.concurrency=100
```

报告输出：`integration-test/target/load-reports/*.json|csv`。

---

## 7. 监控与告警

```text
Prometheus 端点：/actuator/prometheus（各服务）
健康检查：      /actuator/health
关键指标：      gateway_request_total / seckill_success_total /
               inventory_deduct_* / inventory_deadlock_total /
               inventory_reconcile_diff / inventory_redis_consistency_fail_total /
               gateway_canary_* / rocketmq_*（生产侧 exporter）
Grafana：       docs/06-production/grafana/seckill-production-overview.json
告警规则：      docs/06-production/production-alert-rule.md
```

---

## 8. 运维操作

### 库存对账

```bash
# 查询 diff（inventory-service 管理接口，需 admin 签名）
curl http://localhost:8085/api/v1/inventory/admin/reconcile?skuId=20001 \
  -H "X-Service-Name: admin" \
  -H "X-Service-Timestamp: $(date +%s%3N)" \
  -H "X-Service-Nonce: $(uuidgen | tr -d '-')" \
  -H "X-Service-Signature: <HMAC-SHA256(admin:timestamp:nonce, dev-admin-secret)>"
```

### Redis 预热（key 丢失恢复）

Redis AOF 只降低正常重启的数据丢失窗口，不能替代业务恢复流程。发现 key 丢失或
对账不一致时，先暂停入口流量，再按以下顺序执行：

1. 以 MySQL `inventory.available_stock` 和 `inventory_bucket.available_stock` 为准重建
   global、total、bucket key；禁止用 `total_stock` 覆盖 `available_stock`；
2. 调用 `GET /api/v1/inventory/admin/reconcile?skuId=...` 检查
   `Redis.available == SUM(bucket.available) == inventory.available`；
3. 差异经审批后调用 `/api/v1/inventory/admin/reconcile/repair`，保留 REPAIR 流水；
4. 对账通过后恢复流量，并观察 `inventory_redis_consistency_fail_total`。

完整断言见 `docs/06-production/recovery-drill-report.md` 和
`BackupRecoveryDrillIT.redisKeyLossPreheatAndReconcile`。

### 慢 SQL

```sql
SELECT start_time, query_time, sql_text FROM mysql.slow_log
WHERE start_time >= NOW() - INTERVAL 1 HOUR ORDER BY query_time DESC LIMIT 50;
```

---

## 9. 回滚

| 场景 | 操作 | RTO |
| --- | --- | --- |
| Gateway Canary | POST /actuator/canary {"weight":0} | <5min（实测毫秒级） |
| 库存分桶 | 关 `inventory.sharding.enabled=false`（N=1 等价旧模型） | 分钟级 |
| DB 迁移 | inventory_bak 备份恢复（演练 PASS） | 分钟级 |
| MQ | 重复投递幂等，无需人工补偿 | — |

---

## 10. 故障恢复

- Redis 普通重启：AOF `everysec` + `redis-data` 卷恢复；Redis 全量丢失：
  MySQL `available_stock` 预热 + 对账 + repair（REPAIR 流水留痕）；
- MQ consumer 崩溃：重启后积压自动消费，重复消息只生效一次；
- 库存差异：`/api/v1/inventory/admin/reconcile/repair`（仅 admin）；
- 混沌演练：docs/06-production/recovery-drill-report.md、rollback-drill-report.md。

---

## 11. GA 状态与限制

工程/正确性验证全部完成（见 README §六）；剩余 GA 阻塞为外部生产验证资源。
个人项目演示部署不阻塞；生产发布按 personal-project-a1-a5-execution-guide.md 推进。
