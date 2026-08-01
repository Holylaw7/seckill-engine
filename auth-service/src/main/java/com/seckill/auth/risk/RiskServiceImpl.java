package com.seckill.auth.risk;

import com.seckill.auth.constant.AuthConstants;
import com.seckill.auth.entity.RiskRecord;
import com.seckill.auth.mapper.RiskRecordMapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.id.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class RiskServiceImpl implements RiskService {

    private final StringRedisTemplate redisTemplate;
    private final RiskRecordMapper riskRecordMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Override
    public RiskResult check(RiskContext context) {
        if (context.userId() != null && isBlacklisted(AuthConstants.BLACKLIST_TYPE_USER, context.userId())) {
            return RiskResult.reject(ErrorCode.BLACKLISTED, "用户黑名单");
        }
        if (context.ip() != null && isBlacklisted(AuthConstants.BLACKLIST_TYPE_IP, context.ip())) {
            return RiskResult.reject(ErrorCode.BLACKLISTED, "IP 黑名单");
        }
        if (context.deviceFingerprint() != null && !context.deviceFingerprint().isBlank()
                && isBlacklisted(AuthConstants.BLACKLIST_TYPE_DEVICE, context.deviceFingerprint())) {
            return RiskResult.reject(ErrorCode.BLACKLISTED, "设备黑名单");
        }
        if (RiskContext.ACTION_LOGIN.equals(context.action()) && context.userId() != null) {
            long failures = currentLoginFailures(context.userId());
            if (failures >= AuthConstants.LOGIN_FAIL_FREEZE_THRESHOLD) {
                return RiskResult.reject(ErrorCode.RISK_REJECTED, "登录失败次数过多，账号已临时冻结");
            }
            if (failures >= AuthConstants.LOGIN_FAIL_CAPTCHA_THRESHOLD) {
                return RiskResult.captcha("登录异常，需要人机验证");
            }
        }
        return RiskResult.pass();
    }

    @Override
    public long recordLoginFailure(String identity) {
        String key = AuthConstants.LOGIN_FAIL_PREFIX + identity;
        Long count = redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, Duration.ofSeconds(AuthConstants.LOGIN_FAIL_WINDOW_SECONDS));
        return count == null ? 0L : count;
    }

    @Override
    public void resetLoginFailure(String identity) {
        redisTemplate.delete(AuthConstants.LOGIN_FAIL_PREFIX + identity);
    }

    @Override
    public void record(RiskContext context, RiskResult result) {
        if (result.decision() == RiskResult.Decision.PASS) {
            return;
        }
        RiskRecord record = new RiskRecord();
        record.setId(snowflakeIdGenerator.nextId());
        record.setUserId(context.userId() == null ? null : Long.parseLong(context.userId()));
        record.setIp(context.ip());
        record.setDeviceFingerprint(context.deviceFingerprint());
        record.setAction(context.action());
        record.setRiskScore(result.decision() == RiskResult.Decision.REJECT ? 80 : 50);
        record.setDecision(result.decision().name());
        riskRecordMapper.insert(record);
    }

    private boolean isBlacklisted(String type, String value) {
        return redisTemplate.opsForValue().get(blacklistKey(type, value)) != null;
    }

    private long currentLoginFailures(String userId) {
        String value = redisTemplate.opsForValue().get(AuthConstants.LOGIN_FAIL_PREFIX + userId);
        return value == null ? 0L : Long.parseLong(value);
    }

    private String blacklistKey(String type, String value) {
        return AuthConstants.BLACKLIST_PREFIX + type + ":" + value;
    }
}
