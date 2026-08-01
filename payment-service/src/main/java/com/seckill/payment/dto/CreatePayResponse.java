package com.seckill.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class CreatePayResponse {

    private String paymentNo;

    private Map<String, String> channelParams;
}
