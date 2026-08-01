package com.seckill.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RefundResult {

    private String refundNo;

    private String status;

    private String channelRefundNo;
}
