package com.seckill.order.client;

/**
 * seckill-service 预扣确认客户端（/internal/pre-deducts/confirm）。
 */
public interface PreDeductConfirmClient {

    boolean confirm(String messageId, String orderId);
}
