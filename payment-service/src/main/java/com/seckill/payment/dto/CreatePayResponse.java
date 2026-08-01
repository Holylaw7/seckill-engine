package com.seckill.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePayResponse {

    private String paymentNo;

    private Map<String, String> channelParams;
}
