package com.seckill.inventory.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.security.InternalSignature;
import com.seckill.inventory.config.InternalAuthProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class InternalApiAuthFilterTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private FilterChain filterChain;

    private InternalApiAuthFilter filter;
    private final String secret = "admin-secret";

    @BeforeEach
    void setUp() {
        InternalAuthProperties properties = new InternalAuthProperties();
        properties.setEnabled(true);
        properties.setAdminSecret(secret);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        filter = new InternalApiAuthFilter(properties, new ObjectMapper(), redisTemplate);
    }

    @Test
    void validAdminRequestShouldUseSharedRedisNonceStore() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        MockHttpServletRequest request = request("nonce-1");

        filter.doFilter(request, new MockHttpServletResponse(), filterChain);

        verify(valueOperations).setIfAbsent(
                eq("seckill:security:nonce:admin:nonce-1"),
                eq("1"), eq(Duration.ofSeconds(60)));
        verify(filterChain).doFilter(eq(request), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void redisDuplicateShouldBeRejected() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("nonce-1"), response, filterChain);

        assertEquals(401, response.getStatus());
        org.mockito.Mockito.verifyNoInteractions(filterChain);
    }

    private MockHttpServletRequest request(String nonce) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/v1/inventory/admin/reconcile/repair");
        request.addHeader("X-Service-Name", "admin");
        request.addHeader("X-Service-Timestamp", timestamp);
        request.addHeader("X-Service-Nonce", nonce);
        request.addHeader("X-Service-Signature",
                InternalSignature.sign(secret, "admin", timestamp, nonce));
        return request;
    }
}
