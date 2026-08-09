# Seckill-Engine 端到端演示脚本

> 版本：v1.0（2026-08-09 实测通过）
> 前置：`docker compose -f docker/docker-compose.yml up -d --build` 已运行

---

## 0. 初始化（首次或重置后）

```bash
# 插入演示用户（密码 Test@123）
docker exec seckill-mysql-compose mysql -uroot -pseckill-root \
  -e 'INSERT INTO seckill_auth.`user` (id, username, password_hash, status, roles)
      VALUES (10001, "tester", "$2a$10$wIDa2z5fwwlfxInfI0rVEOWwsp3damWoubNwXRUyOUAmdlcZ/Io36", 1, "USER");'

# 分桶行（8 × 125）与 Redis 预热（见 operations-runbook.md §3）
```

重置脚本（每次演示前执行，恢复库存 1000 / 清空订单与流水）：

```bash
docker exec seckill-mysql-compose mysql -uroot -pseckill-root -e \
  'SET FOREIGN_KEY_CHECKS=0; DELETE FROM seckill_order.order_item;
   DELETE FROM seckill_order.seckill_order; DELETE FROM seckill_order.idempotent;
   DELETE FROM seckill_inventory.stock_flow; DELETE FROM seckill_payment.payment_order;
   SET FOREIGN_KEY_CHECKS=1;
   UPDATE seckill_inventory.inventory SET available_stock=1000, locked_stock=0 WHERE sku_id=20001;
   UPDATE seckill_inventory.inventory_bucket SET available_stock=125, locked_stock=0 WHERE sku_id=20001;'
docker exec seckill-redis-compose redis-cli DEL seckill:user:20001:10001
docker exec seckill-redis-compose redis-cli SET seckill:stock:20001 1000
for i in $(seq 0 7); do
  docker exec seckill-redis-compose redis-cli SET seckill:stock:bucket:20001:$i 125
done
```

---

## 1. 登录

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"tester","password":"Test@123"}'
```

预期：`code=0`，返回 JWT。

## 2. 秒杀（Redis Lua 分桶扣减）

```bash
TOKEN=<上一步 token>
curl -X POST http://localhost:8080/api/v1/seckill/execute \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-User-Id: 10001" \
  -d '{"sessionId":30001,"skuId":20001,"quantity":1}'
```

预期：`code=0`，返回 orderId。

## 3. 一致性验证（MQ 消费后）

```bash
# 订单 WAIT_PAY
docker exec seckill-mysql-compose mysql -uroot -pseckill-root -N \
  -e 'SELECT order_no, order_status FROM seckill_order.seckill_order;'

# 分桶不变量：available+locked=total；DEDUCT 流水 1
docker exec seckill-mysql-compose mysql -uroot -pseckill-root -N \
  -e 'SELECT SUM(available_stock), SUM(locked_stock), SUM(total_stock)
      FROM seckill_inventory.inventory_bucket WHERE sku_id=20001;'

# Redis 与 MySQL 一致（均为 999）
docker exec seckill-redis-compose redis-cli GET seckill:stock:20001
```

## 4. 防重

重复执行第 2 步，预期：`code=30005 请勿重复抢购`，库存仍 999。

## 5. 支付（MOCK）

```bash
curl -X POST http://localhost:8080/api/v1/payments/create \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"orderNo":"<ORDER_NO>","userId":10001,"amount":99.00,"channel":"MOCK"}'
```

预期：`code=0`，payment_order 状态 WAIT_PAY。

## 6. 取消 → 回补

```bash
curl -X POST http://localhost:8080/api/v1/orders/<ORDER_NO>/cancel \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN"
```

预期（10s 后）：

```text
RECOVER flow=1
inventory available=1000 locked=0
Redis seckill:stock:20001 = 1000   # Redis == MySQL == 1000
```

---

## 演示讲解要点（面试用）

1. **防超卖**：Redis Lua 原子扣减 + 分桶（N=8）解除单行锁热点；
2. **最终一致性**：RocketMQ 事务消息 → 建单 → 分桶 DEDUCT → 幂等流水（重复消息只生效一次）；
3. **防重**：`seckill:user:{sku}:{uid}` 标记，重复购买返回 30005；
4. **可回滚**：取消 → RECOVER 幂等回补，Redis 与 MySQL 始终一致；
5. **可观测**：/actuator/prometheus（gateway_request_total、seckill_success_total、
   inventory_deadlock_total 等）+ Grafana 面板。

## 清理

```bash
docker compose -f docker/docker-compose.yml down        # 停止
docker compose -f docker/docker-compose.yml down -v     # 停止并清数据
```
