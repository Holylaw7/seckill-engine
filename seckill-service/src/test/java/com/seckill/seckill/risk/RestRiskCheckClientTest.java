package com.seckill.seckill.risk;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.seckill.config.SeckillProperties;
import com.seckill.seckill.dto.RiskCheckRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@org.junit.jupiter.api.Tag("unit")
class RestRiskCheckClientTest {

    private static final String PATH = "/api/v1/auth/internal/risk/check";
    private static final String FULL_URI = "http://localhost:1/api/v1/auth/internal/risk/check";

    private RestRiskCheckClient newClient(SeckillProperties properties) {
        return new RestRiskCheckClient(buildClient().restClient(), properties);
    }

    private record BoundClient(RestClient restClient, MockRestServiceServer server) {
    }

    private BoundClient buildClient() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new BoundClient(builder.build(), server);
    }

    private SeckillProperties properties(boolean enabled, boolean failOpen) {
        SeckillProperties properties = new SeckillProperties();
        properties.getRiskCheck().setEnabled(enabled);
        properties.getRiskCheck().setFailOpen(failOpen);
        return properties;
    }

    private RiskCheckRequest request() {
        return new RiskCheckRequest("10001", "127.0.0.1", "SECKILL", null);
    }

    @Test
    void check_should_skip_when_disabled() {
        SeckillProperties properties = properties(false, false);
        assertDoesNotThrow(() -> newClient(properties).check(request()));
    }

    @Test
    void check_should_pass_when_risk_returns_zero() {
        SeckillProperties properties = properties(true, false);
        BoundClient bound = buildClient();
        RestClient restClient = bound.restClient();
        MockRestServiceServer server = bound.server();
        server.expect(requestTo(FULL_URI)).andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));

        new RestRiskCheckClient(restClient, properties).check(request());
        server.verify();
    }

    @Test
    void check_should_reject_when_risk_returns_error_code() {
        SeckillProperties properties = properties(true, false);
        BoundClient bound = buildClient();
        RestClient restClient = bound.restClient();
        MockRestServiceServer server = bound.server();
        server.expect(requestTo(FULL_URI)).andRespond(
                withSuccess("{\"code\":20003,\"message\":\"风控拦截\"}", MediaType.APPLICATION_JSON));

        BusinessException e = assertThrows(BusinessException.class,
                () -> new RestRiskCheckClient(restClient, properties).check(request()));
        assertEquals(ErrorCode.RISK_REJECTED, e.getErrorCode());
        server.verify();
    }

    @Test
    void check_should_fail_open_when_risk_unavailable() {
        SeckillProperties properties = properties(true, true);
        BoundClient bound = buildClient();
        RestClient restClient = bound.restClient();
        MockRestServiceServer server = bound.server();
        server.expect(requestTo(FULL_URI)).andRespond(withServerError());

        assertDoesNotThrow(() -> new RestRiskCheckClient(restClient, properties).check(request()));
        server.verify();
    }

    @Test
    void check_should_fail_closed_when_risk_unavailable() {
        SeckillProperties properties = properties(true, false);
        BoundClient bound = buildClient();
        RestClient restClient = bound.restClient();
        MockRestServiceServer server = bound.server();
        server.expect(requestTo(FULL_URI)).andRespond(withServerError());

        BusinessException e = assertThrows(BusinessException.class,
                () -> new RestRiskCheckClient(restClient, properties).check(request()));
        assertEquals(ErrorCode.SECKILL_BUSY, e.getErrorCode());
        server.verify();
    }
}
