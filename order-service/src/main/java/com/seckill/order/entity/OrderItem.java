package com.seckill.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("order_item")
public class OrderItem {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long orderId;

    private Long userId;

    private Long skuId;

    private String skuName;

    private Integer quantity;

    private BigDecimal price;

    private BigDecimal amount;

    private LocalDateTime createdAt;
}
