# 覆盖率缺口分析（Phase 5.7.1）

分析基线：Phase 5.7 结束时 JaCoCo 报告（seckill-service 68.26%，payment-service 70.22%）。

---

## 1. seckill-service

### 1.1 缺口清单

| 文件 | 修复前覆盖 | 缺失分支 | 补充方案 |
| --- | --- | --- | --- |
| controller/SeckillController | 0% | execute 成功/缺头、result、session 存在/缺失、sessions 分页 | SeckillControllerTest（MockMvc + GlobalExceptionHandler） |
| controller/PreDeductInternalController | 0% | confirm 成功、参数校验拒绝 | PreDeductInternalControllerTest |
| controller/StockRecoverInternalController | 0% | 回补成功、requestId 幂等、NOT_READY 失败、参数校验 | StockRecoverInternalControllerTest |
| risk/RestRiskCheckClient | 0% | 开关关闭、code=0、code≠0、fail-open、fail-closed | RestRiskCheckClientTest（MockRestServiceServer） |
| service/PreDeductService | 70% | markTxSuccess、findLatestByUserSessionSku | PreDeductServiceTest 扩展 |
| mq/TransactionListenerImpl | 87.5% | byte[] payload 解析、重复消息状态未知 → COMMIT | TransactionListenerImplTest 扩展 |

### 1.2 补充后结果

行覆盖率 68.26% → 91.69%（门禁 ≥80%，PASS）。

---

## 2. payment-service

### 2.1 缺口清单

| 文件 | 修复前覆盖 | 缺失分支 | 补充方案 |
| --- | --- | --- | --- |
| channel/PaymentChannelRouter | 0% | 已知渠道、未知渠道拒绝 | PaymentChannelRouterTest |
| controller/PaymentController | 0% | create/query/refund 成功、not found、缺头、paymentNo 不一致 | PaymentControllerTest（MockMvc） |
| service/PaymentService | 74.47% | query 成功/不存在、failPayment、startRefund、completeRefundSuccess、建单流转失败 | PaymentServiceTest 扩展 |
| channel/MockPaymentChannel | 77.27% | 空/空白签名拒绝、query 返回 SUCCESS | MockPaymentChannelTest 扩展 |
| service/RefundService | 78.57% | startRefund 失败、retry 不存在、查询失败单、插入冲突回退 | RefundServiceTest 扩展 |
| service/CallbackHandler | 82.61% | 支付单不存在回退日志、渠道状态非 SUCCESS、金额异常更新既有日志行 | CallbackHandlerTest 扩展 |

### 2.2 补充后结果

行覆盖率 70.22% → 86.21%（门禁 ≥75%，PASS）。

---

## 3. 验证结果

| 模块 | 修复前 | 修复后 | 门禁 | 结果 |
| --- | --- | --- | --- | --- |
| seckill-service | 68.26% | 91.69% | ≥80% | PASS |
| payment-service | 70.22% | 86.21% | ≥75% | PASS |
| 合计 | 76.49% | 84.35% | ≥70% | PASS |

`bash scripts/release-check.sh` → Gate: PASS。
