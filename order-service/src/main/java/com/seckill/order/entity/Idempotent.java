package com.seckill.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("idempotent")
public class Idempotent {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String bizType;

    private String bizId;

    private Long userId;

    private String resultCode;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
