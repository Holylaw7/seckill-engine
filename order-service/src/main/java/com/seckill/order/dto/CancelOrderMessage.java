package com.seckill.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CANCEL_ORDER 消息（冻结字段）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CancelOrderMessage {

    private String messageId;

    private String orderId;

    private Long userId;

    private Long skuId;

    private Long sessionId;

    private Integer quantity;

    /** CANCEL / TIMEOUT */
    private String reason;

    private Long timestamp;
}
