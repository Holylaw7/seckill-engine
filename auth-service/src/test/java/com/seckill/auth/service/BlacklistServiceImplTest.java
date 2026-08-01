package com.seckill.auth.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.seckill.auth.dto.BlacklistRequest;
import com.seckill.auth.entity.RiskBlacklist;
import com.seckill.auth.mapper.RiskBlacklistMapper;
import com.seckill.auth.service.impl.BlacklistServiceImpl;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.common.result.PageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlacklistServiceImplTest {

    @Mock
    private RiskBlacklistMapper riskBlacklistMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private BlacklistServiceImpl newService() {
        return new BlacklistServiceImpl(riskBlacklistMapper, redisTemplate, snowflakeIdGenerator);
    }

    private BlacklistRequest request() {
        BlacklistRequest request = new BlacklistRequest();
        request.setBizType("USER");
        request.setBizValue("10001");
        request.setReason("恶意刷单");
        return request;
    }

    @Test
    void addShouldInsertAndSyncRedis() {
        when(snowflakeIdGenerator.nextId()).thenReturn(1L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        newService().add(request(), 9L);

        ArgumentCaptor<RiskBlacklist> captor = ArgumentCaptor.forClass(RiskBlacklist.class);
        verify(riskBlacklistMapper).insert(captor.capture());
        assertEquals("user", captor.getValue().getBizType());
        assertEquals("10001", captor.getValue().getBizValue());
        assertEquals(9L, captor.getValue().getOperatorId());
        verify(valueOperations).set("risk:blacklist:user:10001", "1");
    }

    @Test
    void addDuplicateShouldBeIdempotentAndSyncRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new DuplicateKeyException("dup")).when(riskBlacklistMapper).insert(any(RiskBlacklist.class));
        newService().add(request(), 9L);
        verify(valueOperations).set("risk:blacklist:user:10001", "1");
    }

    @Test
    void removeShouldDisableAndDeleteRedisKey() {
        RiskBlacklist entity = new RiskBlacklist();
        entity.setId(1L);
        entity.setStatus(1);
        when(riskBlacklistMapper.selectOne(any())).thenReturn(entity);
        newService().remove("USER", "10001");
        verify(riskBlacklistMapper).updateById(any(RiskBlacklist.class));
        verify(redisTemplate).delete("risk:blacklist:user:10001");
    }

    @Test
    void listShouldMapPage() {
        Page<RiskBlacklist> page = new Page<>(1, 10);
        RiskBlacklist entity = new RiskBlacklist();
        entity.setId(1L);
        page.setRecords(List.of(entity));
        page.setTotal(1);
        when(riskBlacklistMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<RiskBlacklist> result = newService().list(1, 10);
        assertEquals(1, result.getList().size());
        assertEquals(1, result.getTotal());
    }
}
