package com.seckill.order.client;

import com.seckill.order.config.OrderProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@org.junit.jupiter.api.Tag("unit")
class RestPreDeductConfirmClientTest {

    private static final String BASE_URL = "http://localhost:8082";

    @Test
    void confirmSuccessShouldReturnTrue() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OrderProperties properties = new OrderProperties();
        properties.getPreDeductConfirm().setBaseUrl(BASE_URL);
        RestPreDeductConfirmClient client =
                new RestPreDeductConfirmClient(builder.build(), properties);

        server.expect(requestTo(BASE_URL + "/api/v1/seckill/internal/pre-deducts/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"code\":0,\"message\":\"success\",\"data\":null,\"timestamp\":1,\"traceId\":null}",
                        MediaType.APPLICATION_JSON));

        assertTrue(client.confirm("msg-001", "123"));
        server.verify();
    }

    @Test
    void confirmServerErrorShouldReturnFalse() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OrderProperties properties = new OrderProperties();
        properties.getPreDeductConfirm().setBaseUrl(BASE_URL);
        RestPreDeductConfirmClient client =
                new RestPreDeductConfirmClient(builder.build(), properties);

        server.expect(requestTo(BASE_URL + "/api/v1/seckill/internal/pre-deducts/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertFalse(client.confirm("msg-001", "123"));
    }
}
