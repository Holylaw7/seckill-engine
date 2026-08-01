package com.seckill.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class LoginResponse {

    private String token;

    private String userId;

    private String username;

    private List<String> roles;

    private Long expiresAt;
}
