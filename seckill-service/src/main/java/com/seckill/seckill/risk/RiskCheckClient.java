package com.seckill.seckill.risk;

import com.seckill.seckill.dto.RiskCheckRequest;

/**
 * 风控客户端（调用 auth-service /internal/risk/check，异常 fail-open）。
 */
public interface RiskCheckClient {

    void check(RiskCheckRequest request);
}
