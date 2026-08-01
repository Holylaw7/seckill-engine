package com.seckill.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SessionResponse {

    private String userId;

    private String username;

    private String tokenVersion;

    private Long loginTime;

    private Long lastActiveTime;

    private String device;
}
