package com.seckill.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class OrderStatusResponse {

    private String orderStatus;

    private LocalDateTime payDeadline;
}
