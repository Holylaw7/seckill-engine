package com.seckill.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CREATE_ORDER 消息（冻结：amount 单位分，v1.1）。
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

    /** 金额快照（分，Long） */
    private Long amount;

    private String traceId;
}
