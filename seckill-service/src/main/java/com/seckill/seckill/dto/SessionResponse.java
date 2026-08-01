package com.seckill.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SessionResponse {

    private Long sessionId;

    private Long activityId;

    private String name;

    private Long startTime;

    private Long endTime;

    private String status;

    private Integer limitPerUser;
}
