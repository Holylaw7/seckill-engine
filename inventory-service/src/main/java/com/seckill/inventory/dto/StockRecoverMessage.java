package com.seckill.inventory.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * STOCK_RECOVER / CANCEL_ORDER 消息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockRecoverMessage {

    private String messageId;

    private String orderId;

    private Long userId;

    private Long skuId;

    private Long sessionId;

    private Integer quantity;

    /** CANCEL / TIMEOUT / REFUND */
    private String reason;

    private Long timestamp;
}
