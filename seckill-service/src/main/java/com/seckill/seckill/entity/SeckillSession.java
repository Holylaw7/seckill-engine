package com.seckill.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("seckill_session")
public class SeckillSession {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long activityId;

    private String sessionName;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** INIT / PREHEATING / READY / ONGOING / ENDED */
    private String status;

    private Integer limitPerUser;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
