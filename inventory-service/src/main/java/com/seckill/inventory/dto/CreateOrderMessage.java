package com.seckill.inventory.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CREATE_ORDER 消息（冻结字段，messageId 全链路唯一）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderMessage {

    private String messageId;

    private Long userId;

    private Long skuId;

    private Long sessionId;

    private String orderId;

    private Long timestamp;

    private Integer quantity;

    private String traceId;
}
