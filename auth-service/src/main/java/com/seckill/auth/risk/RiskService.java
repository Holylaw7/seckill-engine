package com.seckill.auth.risk;

/**
 * 风控服务接口（冻结，未来可抽离 risk-service）。
 */
public interface RiskService {

    RiskResult check(RiskContext context);

    void record(RiskContext context, RiskResult result);

    long recordLoginFailure(String identity);

    void resetLoginFailure(String identity);
}
