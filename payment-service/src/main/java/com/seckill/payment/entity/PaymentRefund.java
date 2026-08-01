package com.seckill.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("payment_refund")
public class PaymentRefund {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String refundNo;

    private String paymentNo;

    private String orderNo;

    private Long userId;

    private BigDecimal amount;

    private String status;

    private String channelRefundNo;

    private Integer version;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
