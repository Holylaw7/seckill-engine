package com.seckill.auth.filter;

import com.seckill.auth.constant.AuthConstants;
import com.seckill.common.trace.TraceIdUtils;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void shouldGenerateAndClearTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/session");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        String traceId = response.getHeader(AuthConstants.HEADER_TRACE_ID);
        assertNotNull(traceId);
        assertEquals(32, traceId.length());
        assertNull(TraceIdUtils.get());
    }

    @Test
    void shouldReuseIncomingTraceId() throws Exception {
        String incoming = "b".repeat(32);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/session");
        request.addHeader(AuthConstants.HEADER_TRACE_ID, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(incoming, response.getHeader(AuthConstants.HEADER_TRACE_ID));
    }
}
