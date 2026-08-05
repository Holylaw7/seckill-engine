package com.seckill.integration.integration;

import com.seckill.common.security.InternalSignature;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.seckill.SeckillApplication;
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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.6.1 Internal API Security：
 * 内部 recover 接口未授权 401、错误服务 403、授权 200、重放拒绝。
 */
@Tag("integration")
class InternalApiSecurityIT extends IntegrationTestBase {

    private static final RestTemplate REST = rest();

    @Test
    void internalRecoverShouldEnforceServiceAcl() throws Exception {
        // 模拟已扣减 1：stock=99,total=100，恢复 1 后回到 100（避免 OVER_TOTAL 干扰鉴权断言）
        redisSet("seckill:stock:97001", "99");
        redisSet("seckill:stock:total:97001", "100");
        ServiceLauncher.RunningService seckill = ServiceSupport.start(
                SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false");
        try {
            String url = "http://localhost:" + seckill.port()
                    + "/api/v1/seckill/internal/stocks/recover";
            String body = "{\"requestId\":\"drill-recover-1\",\"skuId\":97001,"
                    + "\"sessionId\":97001,\"recoverCount\":1}";

            // 未授权：401
            ResponseEntity<String> unauthorized = post(url, body, null, null, null);
            assertThat(unauthorized.getStatusCode().value()).isEqualTo(401);

            // 错误服务：order-service 签名访问 recover → 403
            String ts = String.valueOf(System.currentTimeMillis());
            String orderSig = InternalSignature.sign("dev-order-secret", "order-service:" + ts);
            assertThat(post(url, body, "order-service", ts, orderSig).getStatusCode().value())
                    .isEqualTo(403);

            // 授权：inventory-service → 200
            String validTs = String.valueOf(System.currentTimeMillis());
            String validSig = InternalSignature.sign("dev-inventory-secret",
                    "inventory-service:" + validTs);
            ResponseEntity<String> ok = post(url, body, "inventory-service", validTs, validSig);
            assertThat(ok.getStatusCode().value()).isEqualTo(200);
            assertThat(ok.getBody()).contains("\"code\":0");

            // 重放：同一时间戳+签名 → 401
            ResponseEntity<String> replay = post(url, body, "inventory-service", validTs, validSig);
            assertThat(replay.getStatusCode().value()).isEqualTo(401);
        } finally {
            seckill.stop();
            cleanRedis("seckill:*");
        }
    }

    private static ResponseEntity<String> post(String url, String body, String name,
                                               String timestamp, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (name != null) {
            headers.set("X-Service-Name", name);
            headers.set("X-Service-Timestamp", timestamp);
            headers.set("X-Service-Signature", signature);
        }
        return REST.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
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
