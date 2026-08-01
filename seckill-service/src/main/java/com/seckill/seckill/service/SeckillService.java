package com.seckill.seckill.service;

import com.seckill.seckill.dto.ExecuteRequest;
import com.seckill.seckill.dto.ExecuteResponse;

public interface SeckillService {

    ExecuteResponse execute(Long userId, String ip, ExecuteRequest request);
}
