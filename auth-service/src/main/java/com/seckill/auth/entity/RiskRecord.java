package com.seckill.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("risk_record")
public class RiskRecord {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long userId;

    private String ip;

    private String deviceFingerprint;

    /** LOGIN / SECKILL / PAY */
    private String action;

    private Integer riskScore;

    /** PASS / REJECT / CAPTCHA */
    private String decision;

    private LocalDateTime createdAt;
}
