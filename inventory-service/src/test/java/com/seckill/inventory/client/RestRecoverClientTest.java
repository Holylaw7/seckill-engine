package com.seckill.inventory.client;

import com.seckill.inventory.config.InventoryProperties;
import com.seckill.inventory.dto.RecoverRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@org.junit.jupiter.api.Tag("unit")
class RestRecoverClientTest {

    private static final String BASE_URL = "http://localhost:8082";

    @Test
    void recoverSuccessShouldReturnTrue() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        InventoryProperties properties = new InventoryProperties();
        properties.getRecover().setBaseUrl(BASE_URL);
        RestRecoverClient client = new RestRecoverClient(builder.build(), properties);
        ReflectionTestUtils.setField(client, "clientName", "inventory-service");
        ReflectionTestUtils.setField(client, "clientSecret", "dev-inventory-secret");

        server.expect(requestTo(BASE_URL + "/api/v1/seckill/internal/stocks/recover"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"code\":0,\"message\":\"success\",\"data\":null,\"timestamp\":1,\"traceId\":null}",
                        MediaType.APPLICATION_JSON));

        assertTrue(client.recover(new RecoverRequest("SF1", 20001L, 30001L, 1, null)));
        server.verify();
    }

    @Test
    void recoverServerErrorShouldReturnFalse() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        InventoryProperties properties = new InventoryProperties();
        properties.getRecover().setBaseUrl(BASE_URL);
        RestRecoverClient client = new RestRecoverClient(builder.build(), properties);
        ReflectionTestUtils.setField(client, "clientName", "inventory-service");
        ReflectionTestUtils.setField(client, "clientSecret", "dev-inventory-secret");

        server.expect(requestTo(BASE_URL + "/api/v1/seckill/internal/stocks/recover"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertFalse(client.recover(new RecoverRequest("SF1", 20001L, 30001L, 1, null)));
    }
}
