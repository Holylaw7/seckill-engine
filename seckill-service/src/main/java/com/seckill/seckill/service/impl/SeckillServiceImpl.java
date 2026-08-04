package com.seckill.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.seckill.config.SeckillProperties;
import com.seckill.seckill.config.SeckillShardingProperties;
import com.seckill.seckill.constant.SeckillConstants;
import com.seckill.seckill.dto.ExecuteRequest;
import com.seckill.seckill.dto.ExecuteResponse;
import com.seckill.seckill.dto.RiskCheckRequest;
import com.seckill.seckill.dto.SeckillOrderMessage;
import com.seckill.seckill.entity.SeckillSku;
import com.seckill.seckill.mapper.SeckillSkuMapper;
import com.seckill.seckill.mq.RocketMqProducer;
import com.seckill.seckill.redis.StockDeductResult;
import com.seckill.seckill.redis.BucketDeductResult;
import com.seckill.seckill.redis.StockService;
import com.seckill.seckill.risk.RiskCheckClient;
import com.seckill.seckill.service.SessionCacheService;
import com.seckill.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private final SessionCacheService sessionCacheService;
    private final StockService stockService;
    private final SeckillSkuMapper skuMapper;
    private final RocketMqProducer mqProducer;
    private final RiskCheckClient riskCheckClient;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final SeckillProperties properties;
    private final SeckillShardingProperties shardingProperties;

    @Override
    public ExecuteResponse execute(Long userId, String ip, ExecuteRequest request) {
        if (request.getQuantity() == null || request.getQuantity() < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "数量必须大于0");
        }

        SessionCacheService.SessionCache session = sessionCacheService.getSession(request.getSessionId());
        if (session == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "场次不存在");
        }
        long now = System.currentTimeMillis();
        validateSession(session, now, request.getQuantity());

        if (properties.getRiskCheck().isEnabled()) {
            riskCheckClient.check(new RiskCheckRequest(
                    String.valueOf(userId), ip, RiskCheckRequestAction.SECKILL, null));
        }

        SeckillSku sku = skuMapper.selectOne(new LambdaQueryWrapper<SeckillSku>()
                .eq(SeckillSku::getSessionId, request.getSessionId())
                .eq(SeckillSku::getSkuId, request.getSkuId()));
        if (sku == null || sku.getPrice() == null) {
            throw new BusinessException(ErrorCode.INVENTORY_ERROR, "秒杀商品不存在");
        }
        long amountFen = sku.getPrice().multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();

        long userKeyTtlSeconds = Math.max(1L,
                (session.endTime() + SeckillConstants.USER_MARK_EXTRA_MILLIS - now) / 1000);
        String skuId = String.valueOf(request.getSkuId());
        String userIdStr = String.valueOf(userId);
        StockDeductResult deduct;
        Integer bucketNo = null;
        if (shardingProperties.isEnabled()) {
            BucketDeductResult bucketResult = stockService.preDeductBucket(
                    skuId, userIdStr, request.getQuantity(), userKeyTtlSeconds,
                    shardingProperties.getBucketCount(), 7 * 24 * 3600L);
            deduct = bucketResult.result();
            bucketNo = bucketResult.bucketNo();
        } else {
            deduct = stockService.preDeduct(skuId, userIdStr, request.getQuantity(), userKeyTtlSeconds);
        }
        if (deduct != StockDeductResult.SUCCESS) {
            throw mapDeductError(deduct);
        }

        long orderId = snowflakeIdGenerator.nextId();
        SeckillOrderMessage message = new SeckillOrderMessage(
                UUID.randomUUID().toString().replace("-", ""),
                userId, request.getSkuId(), request.getSessionId(),
                String.valueOf(orderId), now, request.getQuantity(), null, amountFen, bucketNo);
        try {
            mqProducer.sendCreateOrder(message);
        } catch (BusinessException e) {
            // 发送失败：回补库存，禁止重试预扣
            stockService.recoverBucket(skuId, userIdStr, request.getQuantity(), true, bucketNo);
            throw new BusinessException(ErrorCode.SECKILL_BUSY, "系统繁忙，请稍后重试");
        }

        return new ExecuteResponse("SUCCESS", String.valueOf(orderId), now + SeckillConstants.PAY_DEADLINE_MILLIS);
    }

    private static void validateSession(SessionCacheService.SessionCache session, long now, int quantity) {
        String status = session.status();
        if (SeckillConstants.SESSION_STATUS_INIT.equals(status)
                || SeckillConstants.SESSION_STATUS_PREHEATING.equals(status)) {
            throw new BusinessException(ErrorCode.SESSION_NOT_READY);
        }
        if (now < session.startTime()) {
            throw new BusinessException(ErrorCode.SESSION_NOT_STARTED);
        }
        if (now > session.endTime()) {
            throw new BusinessException(ErrorCode.SESSION_ENDED);
        }
        if (quantity > session.limitPerUser()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "超出限购数量");
        }
    }

    private static BusinessException mapDeductError(StockDeductResult result) {
        return switch (result) {
            case STOCK_EMPTY -> new BusinessException(ErrorCode.STOCK_EMPTY);
            case REPEAT_BUY -> new BusinessException(ErrorCode.REPEAT_BUY);
            default -> new BusinessException(ErrorCode.SESSION_NOT_READY);
        };
    }

    private static final class RiskCheckRequestAction {
        private static final String SECKILL = "SECKILL";
    }
}
