package com.seckill.integration.integration;

import com.seckill.common.security.InternalSignature;
import com.seckill.common.util.JsonUtils;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.inventory.InventoryApplication;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.6.2 Repair 权限：
 * 查询 diff（operator/service）200；repair（admin）200、operator 403。
 */
@Tag("integration")
class RepairAuthorizationIT extends IntegrationTestBase {

    private static final RestTemplate REST = rest();
    private static final long SKU_ID = 98001L;

    @Test
    void repairShouldRequireAdminRole() throws Exception {
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        execute("INSERT INTO seckill_inventory.inventory "
                + "(id, sku_id, total_stock, locked_stock, available_stock, version) VALUES ("
                + (50000 + SKU_ID) + ", " + SKU_ID + ", 100, 0, 100, 0)");
        ServiceLauncher.RunningService inventory = ServiceSupport.start(
                InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service");
        try {
            String base = "http://localhost:" + inventory.port() + "/api/v1/inventory/admin";
            // 未授权查询 → 401
            assertThat(get(base + "/reconcile?skuId=" + SKU_ID, null, null, null)
                    .getStatusCode().value()).isEqualTo(401);
            // operator/service 查询 diff → 200
            String operatorTs = String.valueOf(System.currentTimeMillis());
            String operatorSig = InternalSignature.sign("dev-inventory-secret",
                    "inventory-service:" + operatorTs);
            assertThat(get(base + "/reconcile?skuId=" + SKU_ID, "inventory-service",
                    operatorTs, operatorSig).getStatusCode().value()).isEqualTo(200);

            Map<String, Object> repairBody = new LinkedHashMap<>();
            repairBody.put("skuId", SKU_ID);
            repairBody.put("targetAvailable", 90);
            repairBody.put("reason", "drill");
            String repairJson = JsonUtils.toJson(repairBody);

            // operator 执行 repair → 403
            String repairTs = String.valueOf(System.currentTimeMillis());
            String repairSig = InternalSignature.sign("dev-inventory-secret",
                    "inventory-service:" + repairTs);
            assertThat(post(base + "/reconcile/repair", repairJson, "inventory-service",
                    repairTs, repairSig).getStatusCode().value()).isEqualTo(403);

            // admin 执行 repair → 200
            String adminTs = String.valueOf(System.currentTimeMillis());
            String adminSig = InternalSignature.sign("dev-admin-secret", "admin:" + adminTs);
            ResponseEntity<String> ok = post(base + "/reconcile/repair", repairJson,
                    "admin", adminTs, adminSig);
            assertThat(ok.getStatusCode().value()).isEqualTo(200);
            assertThat(ok.getBody()).contains("\"code\":0");
        } finally {
            inventory.stop();
            execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        }
    }

    private static ResponseEntity<String> get(String url, String name, String timestamp, String signature) {
        return REST.exchange(url, HttpMethod.GET, new HttpEntity<>(headers(name, timestamp, signature)),
                String.class);
    }

    private static ResponseEntity<String> post(String url, String body, String name,
                                               String timestamp, String signature) {
        HttpHeaders headers = headers(name, timestamp, signature);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return REST.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpHeaders headers(String name, String timestamp, String signature) {
        HttpHeaders headers = new HttpHeaders();
        if (name != null) {
            headers.set("X-Service-Name", name);
            headers.set("X-Service-Timestamp", timestamp);
            headers.set("X-Service-Signature", signature);
        }
        return headers;
    }

    private static RestTemplate rest() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(15));
        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) {
                // 状态码由断言读取
            }
        });
        return restTemplate;
    }
}
