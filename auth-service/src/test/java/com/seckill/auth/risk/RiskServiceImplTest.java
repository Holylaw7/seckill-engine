package com.seckill.auth.risk;

import com.seckill.auth.entity.RiskRecord;
import com.seckill.auth.mapper.RiskRecordMapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class RiskServiceImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private RiskRecordMapper riskRecordMapper;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private RiskServiceImpl newService() {
        return new RiskServiceImpl(redisTemplate, riskRecordMapper, snowflakeIdGenerator);
    }

    @Test
    void userBlacklistShouldReject() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("risk:blacklist:user:10001")).thenReturn("1");
        RiskResult result = newService().check(new RiskContext("10001", "1.1.1.1", RiskContext.ACTION_LOGIN, null));
        assertEquals(RiskResult.Decision.REJECT, result.decision());
        assertEquals(ErrorCode.BLACKLISTED, result.errorCode());
    }

    @Test
    void loginFreezeThresholdShouldReject() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(valueOperations.get("risk:login:fail:10001")).thenReturn("10");
        RiskResult result = newService().check(new RiskContext("10001", "1.1.1.1", RiskContext.ACTION_LOGIN, null));
        assertEquals(RiskResult.Decision.REJECT, result.decision());
        assertEquals(ErrorCode.RISK_REJECTED, result.errorCode());
    }

    @Test
    void captchaThresholdShouldTrigger() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(valueOperations.get("risk:login:fail:10001")).thenReturn("6");
        RiskResult result = newService().check(new RiskContext("10001", "1.1.1.1", RiskContext.ACTION_LOGIN, null));
        assertEquals(RiskResult.Decision.CAPTCHA, result.decision());
    }

    @Test
    void normalCheckShouldPass() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RiskResult result = newService().check(new RiskContext("10001", "1.1.1.1", RiskContext.ACTION_LOGIN, null));
        assertEquals(RiskResult.Decision.PASS, result.decision());
    }

    @Test
    void recordLoginFailureShouldIncrementAndExpire() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("risk:login:fail:10001")).thenReturn(3L);
        long count = newService().recordLoginFailure("10001");
        assertEquals(3L, count);
        verify(redisTemplate).expire(eq("risk:login:fail:10001"), any(Duration.class));
    }

    @Test
    void recordShouldInsertRiskRowWhenRejected() {
        when(snowflakeIdGenerator.nextId()).thenReturn(1L);
        RiskResult result = RiskResult.reject(ErrorCode.BLACKLISTED, "用户黑名单");
        newService().record(new RiskContext("10001", "1.1.1.1", RiskContext.ACTION_LOGIN, null), result);
        verify(riskRecordMapper).insert(any(RiskRecord.class));
    }

    @Test
    void recordShouldSkipWhenPass() {
        newService().record(new RiskContext("10001", "1.1.1.1", RiskContext.ACTION_LOGIN, null), RiskResult.pass());
        verify(riskRecordMapper, org.mockito.Mockito.never()).insert(any(RiskRecord.class));
        assertTrue(true);
    }
}
