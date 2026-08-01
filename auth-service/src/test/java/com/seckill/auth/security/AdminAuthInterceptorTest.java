package com.seckill.auth.security;

import com.seckill.auth.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuthInterceptorTest {

    private static final String SECRET = "test-secret";

    private AdminAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        interceptor = new AdminAuthInterceptor(properties);
    }

    @Test
    void missingTokenShouldReturn401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/admin/blacklist");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":20001"));
    }

    @Test
    void nonAdminTokenShouldReturn403() throws Exception {
        String token = JwtTokenIssuer.issue("10001", "alice", List.of("USER"), SECRET, 120);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/admin/blacklist");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":20002"));
    }

    @Test
    void adminTokenShouldPass() throws Exception {
        String token = JwtTokenIssuer.issue("10001", "admin", List.of("USER", "ADMIN"), SECRET, 120);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/admin/blacklist");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request, response, new Object()));
    }
}
