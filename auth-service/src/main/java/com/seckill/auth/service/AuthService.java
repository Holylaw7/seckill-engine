package com.seckill.auth.service;

import com.seckill.auth.dto.LoginRequest;
import com.seckill.auth.dto.LoginResponse;
import com.seckill.auth.dto.SessionResponse;

public interface AuthService {

    LoginResponse login(LoginRequest request, String ip, String device);

    void logout(String userId);

    SessionResponse getSession(String userId);
}
