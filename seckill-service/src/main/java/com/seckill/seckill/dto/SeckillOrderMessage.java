package com.seckill.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 建单消息（冻结：seckill-order-tx / CREATE_ORDER，messageId 全链路唯一）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage {

    private String messageId;

    private Long userId;

    private Long skuId;

    private Long sessionId;

    private String orderId;

    private Long timestamp;

    private Integer quantity;

    private String traceId;

    /** 金额快照（单位：分，冻结：order-service 建单依据，禁止客户端传入） */
    private Long amount;

    /** 命中桶号（Phase 6.2 可选；NULL=旧准入路径） */
    private Integer bucketNo;
}
