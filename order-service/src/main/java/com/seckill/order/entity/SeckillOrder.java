package com.seckill.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("seckill_order")
public class SeckillOrder {

    /** 主键 = orderId（Snowflake） */
    @TableId(type = IdType.INPUT)
    private Long id;

    private String orderNo;

    private Long userId;

    private Long sessionId;

    private Long skuId;

    private Integer quantity;

    private BigDecimal orderAmount;

    /** CREATE / WAIT_PAY / PAY_SUCCESS / CANCEL / TIMEOUT / REFUND */
    private String orderStatus;

    /** user_id:session_id:sku_id，终态置 NULL */
    private String activeKey;

    /** PENDING / SENT */
    private String cancelNotifyStatus;

    private LocalDateTime payDeadline;

    private LocalDateTime paidAt;

    private String cancelReason;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
