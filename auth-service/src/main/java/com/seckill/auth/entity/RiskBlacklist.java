package com.seckill.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("risk_blacklist")
public class RiskBlacklist {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** USER / IP / DEVICE（小写存储） */
    private String bizType;

    private String bizValue;

    private String reason;

    private Long operatorId;

    private LocalDateTime expireAt;

    /** 1 生效，0 失效 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
