package com.seckill.seckill.service;

import com.seckill.seckill.constant.SeckillConstants;
import com.seckill.seckill.dto.ResultQueryResponse;
import com.seckill.seckill.entity.SeckillPreDeduct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ResultQueryService {

    private final PreDeductService preDeductService;

    public ResultQueryResponse query(Long userId, Long sessionId, Long skuId) {
        SeckillPreDeduct row = preDeductService.findLatestByUserSessionSku(userId, sessionId, skuId);
        if (row == null) {
            return new ResultQueryResponse("NONE", null, null);
        }
        return switch (row.getDeductStatus()) {
            case SeckillConstants.DEDUCT_STATUS_CONFIRMED ->
                    new ResultQueryResponse("SUCCESS", row.getOrderId(), null);
            case SeckillConstants.DEDUCT_STATUS_RECOVERED ->
                    new ResultQueryResponse("FAILED", null, null);
            default -> new ResultQueryResponse("DEDUCTED", row.getOrderId(), null);
        };
    }
}
