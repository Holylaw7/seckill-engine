package com.seckill.seckill.risk;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.result.Result;
import com.seckill.seckill.config.SeckillProperties;
import com.seckill.seckill.dto.RiskCheckRequest;
import com.seckill.seckill.dto.RiskCheckResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class RestRiskCheckClient implements RiskCheckClient {

    private final RestClient riskRestClient;
    private final SeckillProperties properties;

    @Override
    public void check(RiskCheckRequest request) {
        if (!properties.getRiskCheck().isEnabled()) {
            return;
        }
        try {
            Result<RiskCheckResponse> result = riskRestClient.post()
                    .uri("/api/v1/auth/internal/risk/check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Result<RiskCheckResponse>>() {
                    });
            if (result != null && result.getCode() != 0) {
                throw new BusinessException(ErrorCode.RISK_REJECTED,
                        result.getMessage() == null ? "风控拦截" : result.getMessage());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            if (properties.getRiskCheck().isFailOpen()) {
                log.warn("risk check unavailable, fail-open, error={}", e.getMessage());
                return;
            }
            throw new BusinessException(ErrorCode.SECKILL_BUSY, "风控服务不可用");
        }
    }
}
