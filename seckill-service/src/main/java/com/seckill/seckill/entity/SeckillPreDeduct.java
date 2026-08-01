package com.seckill.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("seckill_pre_deduct")
public class SeckillPreDeduct {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String messageId;

    private String orderId;

    private Long userId;

    private Long sessionId;

    private Long skuId;

    private Integer quantity;

    /** INIT / SUCCESS / FAIL */
    private String txStatus;

    /** DEDUCTED / CONFIRMED / RECOVERED */
    private String deductStatus;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
