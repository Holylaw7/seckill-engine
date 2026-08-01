package com.seckill.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("payment_order")
public class PaymentOrder {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String paymentNo;

    private String orderNo;

    private Long userId;

    private BigDecimal amount;

    private String channel;

    private String status;

    private String transactionNo;

    private String activeOrderKey;

    private LocalDateTime payTime;

    private LocalDateTime refundTime;

    private Integer version;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
