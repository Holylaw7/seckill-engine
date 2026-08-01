package com.seckill.order.dto;

import com.seckill.order.entity.OrderItem;
import com.seckill.order.entity.SeckillOrder;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class OrderDetailResponse {

    private Long id;

    private String orderNo;

    private Long userId;

    private Long sessionId;

    private Long skuId;

    private Integer quantity;

    private BigDecimal orderAmount;

    private String orderStatus;

    private LocalDateTime payDeadline;

    private List<OrderItem> items;

    public static OrderDetailResponse of(SeckillOrder order, List<OrderItem> items) {
        return new OrderDetailResponse(order.getId(), order.getOrderNo(), order.getUserId(),
                order.getSessionId(), order.getSkuId(), order.getQuantity(), order.getOrderAmount(),
                order.getOrderStatus(), order.getPayDeadline(), items);
    }
}
