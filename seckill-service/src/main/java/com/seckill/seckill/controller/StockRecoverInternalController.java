package com.seckill.seckill.controller;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.result.Result;
import com.seckill.seckill.config.SeckillProperties;
import com.seckill.seckill.dto.StockRecoverInternalRequest;
import com.seckill.seckill.redis.StockRecoverResult;
import com.seckill.seckill.redis.StockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 内部接口：inventory-service 回补 Redis 热点库存（契约冻结，见 inventory-service 附录 C.1）。
 *
 * <p>复用既有 recover Lua 脚本；按 requestId（=stock_flow.flow_no）幂等；
 * 回补超过 total 时 Lua 返回 OVER_TOTAL，接口抛出库存异常并释放幂等键允许重试。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/seckill/internal")
@RequiredArgsConstructor
public class StockRecoverInternalController {

    private static final String RECOVER_IDEMPOTENT_PREFIX = "seckill:recover:";

    private final StockService stockService;
    private final StringRedisTemplate redisTemplate;
    private final SeckillProperties properties;

    @PostMapping("/stocks/recover")
    public Result<Void> recover(@Valid @RequestBody StockRecoverInternalRequest request) {
        String idempotentKey = RECOVER_IDEMPOTENT_PREFIX + request.getRequestId();
        Boolean first = redisTemplate.opsForValue().setIfAbsent(
                idempotentKey, "1", Duration.ofSeconds(properties.getFlowKeyTtlSeconds()));
        if (!Boolean.TRUE.equals(first)) {
            // 同一 flow_no 已回补：幂等返回
            return Result.success();
        }
        try {
            StockRecoverResult result = stockService.recover(
                    String.valueOf(request.getSkuId()), "0", request.getRecoverCount(), false);
            if (result != StockRecoverResult.SUCCESS) {
                log.warn("internal stock recover failed, requestId={}, skuId={}, result={}",
                        request.getRequestId(), request.getSkuId(), result);
                throw new BusinessException(ErrorCode.INVENTORY_ERROR, "库存回补失败");
            }
            return Result.success();
        } catch (RuntimeException e) {
            redisTemplate.delete(idempotentKey);
            throw e;
        }
    }
}
