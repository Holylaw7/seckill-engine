package com.seckill.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.seckill.auth.constant.AuthConstants;
import com.seckill.auth.dto.BlacklistRequest;
import com.seckill.auth.entity.RiskBlacklist;
import com.seckill.auth.mapper.RiskBlacklistMapper;
import com.seckill.auth.service.BlacklistService;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class BlacklistServiceImpl implements BlacklistService {

    private final RiskBlacklistMapper riskBlacklistMapper;
    private final StringRedisTemplate redisTemplate;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Override
    public void add(BlacklistRequest request, Long operatorId) {
        String bizType = request.getBizType().trim().toLowerCase();
        String bizValue = request.getBizValue().trim();

        RiskBlacklist entity = new RiskBlacklist();
        entity.setId(snowflakeIdGenerator.nextId());
        entity.setBizType(bizType);
        entity.setBizValue(bizValue);
        entity.setReason(request.getReason());
        entity.setOperatorId(operatorId);
        if (request.getExpireAt() != null) {
            entity.setExpireAt(Instant.ofEpochMilli(request.getExpireAt())
                    .atZone(ZoneId.systemDefault()).toLocalDateTime());
        }
        entity.setStatus(1);
        try {
            riskBlacklistMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 已存在：幂等处理，仅刷新 Redis
        }
        syncToRedis(entity);
    }

    @Override
    public void remove(String bizType, String bizValue) {
        String type = bizType.trim().toLowerCase();
        String value = bizValue.trim();
        RiskBlacklist entity = riskBlacklistMapper.selectOne(new LambdaQueryWrapper<RiskBlacklist>()
                .eq(RiskBlacklist::getBizType, type)
                .eq(RiskBlacklist::getBizValue, value));
        if (entity != null && entity.getStatus() == 1) {
            entity.setStatus(0);
            riskBlacklistMapper.updateById(entity);
        }
        redisTemplate.delete(blacklistKey(type, value));
    }

    @Override
    public PageResult<RiskBlacklist> list(int pageNum, int pageSize) {
        Page<RiskBlacklist> page = riskBlacklistMapper.selectPage(new Page<>(pageNum, pageSize), null);
        return PageResult.of(page.getRecords(), page.getTotal(), pageNum, pageSize);
    }

    private void syncToRedis(RiskBlacklist entity) {
        String key = blacklistKey(entity.getBizType(), entity.getBizValue());
        if (entity.getStatus() == 1) {
            if (entity.getExpireAt() != null) {
                long seconds = Math.max(1L,
                        Duration.between(LocalDateTime.now(), entity.getExpireAt()).getSeconds());
                redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(seconds));
            } else {
                redisTemplate.opsForValue().set(key, "1");
            }
        } else {
            redisTemplate.delete(key);
        }
    }

    private String blacklistKey(String type, String value) {
        return AuthConstants.BLACKLIST_PREFIX + type + ":" + value;
    }
}
