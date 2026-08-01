package com.seckill.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("payment_callback_log")
public class PaymentCallbackLog {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String paymentNo;

    private String channelTransactionNo;

    private String callbackBody;

    private String verifyResult;

    private String processStatus;

    private String traceId;

    private LocalDateTime createdTime;
}
