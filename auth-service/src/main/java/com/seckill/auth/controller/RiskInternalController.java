package com.seckill.auth.controller;

import com.seckill.auth.dto.RiskCheckRequest;
import com.seckill.auth.dto.RiskCheckResponse;
import com.seckill.auth.risk.RiskContext;
import com.seckill.auth.risk.RiskResult;
import com.seckill.auth.risk.RiskService;
import com.seckill.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部风控接口（服务间 Feign 调用，/internal 前缀）。
 */
@RestController
@RequestMapping("/api/v1/auth/internal")
@RequiredArgsConstructor
public class RiskInternalController {

    private final RiskService riskService;

    @PostMapping("/risk/check")
    public Result<RiskCheckResponse> check(@RequestBody RiskCheckRequest request) {
        RiskContext context = new RiskContext(
                request.getUserId(), request.getIp(), request.getAction(), request.getDeviceFingerprint());
        RiskResult result = riskService.check(context);
        riskService.record(context, result);
        return Result.success(new RiskCheckResponse(
                result.decision().name(), result.errorCode().getCode(), result.reason()));
    }
}
