package com.seckill.integration.integration;

import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.7 Task 6 Canary Rollback Drill：
 * 权重 100% → 50% → 25% → 0%（全量回滚），同权重下用户粘性稳定，
 * 回滚 RTO &lt;= 5 分钟（实测毫秒级），流量恢复、下游请求正常。
 */
@Tag("integration")
class CanaryRollbackDrillIT extends IntegrationTestBase {

    private static final String TOKEN = "drill-token";
    private static final RestTemplate REST = new RestTemplate();

    @Test
    void canaryWeightRollbackShouldKeepStickyRoutingAndRecoverTraffic() throws Exception {
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            String canary = exchange.getRequestHeaders().getFirst("X-Canary-Version");
            byte[] body = ("{\"code\":0,\"canary\":\"" + (canary == null ? "none" : canary) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        stub.start();
        try {
            ServiceLauncher.RunningService gateway = startGateway(stub.getAddress().getPort());
            try {
                String base = "http://localhost:" + gateway.port();

                // 100%：全部请求进入 RC1
                setWeight(base, 100);
                assertThat(getWithUser(base, "10001")).contains("\"canary\":\"RC1\"");
                assertThat(getWithUser(base, "20002")).contains("\"canary\":\"RC1\"");

                // 50%：同用户在同权重下路由恒定（sticky）
                setWeight(base, 50);
                String targetA = getWithUser(base, "10001");
                String targetB = getWithUser(base, "30003");
                for (int i = 0; i < 5; i++) {
                    assertThat(getWithUser(base, "10001")).isEqualTo(targetA);
                    assertThat(getWithUser(base, "30003")).isEqualTo(targetB);
                }

                // 25%：继续验证粘性
                setWeight(base, 25);
                String target25 = getWithUser(base, "10001");
                for (int i = 0; i < 5; i++) {
                    assertThat(getWithUser(base, "10001")).isEqualTo(target25);
                }

                // 0%：全量回滚 stable，测量 RTO
                long rollbackStart = System.currentTimeMillis();
                setWeight(base, 0);
                assertThat(getWithUser(base, "10001")).contains("\"canary\":\"stable\"");
                long rtoMs = System.currentTimeMillis() - rollbackStart;
                assertThat(rtoMs).isLessThan(5 * 60_000L);

                // 多用户全部 stable，流量恢复（200）
                for (String userId : List.of("40001", "50002", "60003", "70004", "80005")) {
                    assertThat(getWithUser(base, userId)).contains("\"canary\":\"stable\"");
                }
            } finally {
                gateway.stop();
            }
        } finally {
            stub.stop(0);
        }
    }

    private static ServiceLauncher.RunningService startGateway(int stubPort) {
        return ServiceLauncher.start(com.seckill.gateway.GatewayApplication.class, "gateway",
                List.of(
                        "--server.port=0",
                        "--spring.application.name=gateway",
                        "--spring.main.web-application-type=reactive",
                        "--spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
                        "--spring.data.redis.host=" + redisHost(),
                        "--spring.data.redis.port=" + redisPort(),
                        "--seckill.gateway.jwt.secret=seckill-engine-dev-secret-change-me",
                        "--seckill.gateway.canary.enabled=true",
                        "--seckill.gateway.canary.weight=100",
                        "--seckill.gateway.canary.version=RC1",
                        "--seckill.gateway.canary.control-enabled=true",
                        "--seckill.gateway.canary.control-token=" + TOKEN,
                        "--spring.cloud.gateway.routes[0].id=drill-stub",
                        "--spring.cloud.gateway.routes[0].uri=http://127.0.0.1:" + stubPort,
                        "--spring.cloud.gateway.routes[0].predicates[0]=Path=/api/v1/payments/callback/**"));
    }

    private static String getWithUser(String base, String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId);
        headers.set("X-Request-Id", "rollback-" + System.nanoTime());
        ResponseEntity<String> response = REST.exchange(
                base + "/api/v1/payments/callback/drill-" + userId,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    private static void setWeight(String base, int weight) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Canary-Token", TOKEN);
        ResponseEntity<String> response = REST.exchange(
                base + "/actuator/canary", HttpMethod.POST,
                new HttpEntity<>("{\"weight\":" + weight + "}", headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"weight\":" + weight);
    }
}
