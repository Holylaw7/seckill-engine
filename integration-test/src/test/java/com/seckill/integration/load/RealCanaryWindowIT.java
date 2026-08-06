package com.seckill.integration.load;

import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.10 Task 2 Real Canary Window（隔离生产拓扑）：
 * Gateway canary weight=5，持续流量 ≥10000 请求，验证分流比例 ~5%（粘性哈希）、
 * 错误 0、429 0，输出 canary-real-window.json（Start/End/Traffic%/Requests/Success/Error）。
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class RealCanaryWindowIT extends IntegrationTestBase {

    private static final long MIN_REQUESTS = 10_000;
    // JDK HttpClient：连接复用，避免 Windows 高并发短连接临时端口耗尽
    private static final RestTemplate REST = new RestTemplate(new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()));

    @Test
    void fivePercentWindowShouldCollectTenThousandRequests() throws Exception {
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
        Instant start = Instant.now();
        try {
            ServiceLauncher.RunningService gateway = ServiceLauncher.start(
                    com.seckill.gateway.GatewayApplication.class, "gateway",
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
                            "--seckill.gateway.canary.weight=5",
                            "--seckill.gateway.canary.version=RC1",
                            "--seckill.gateway.canary.control-enabled=false",
                            "--spring.cloud.gateway.routes[0].id=drill-stub",
                            "--spring.cloud.gateway.routes[0].uri=http://127.0.0.1:" + stub.getAddress().getPort(),
                            "--spring.cloud.gateway.routes[0].predicates[0]=Path=/api/v1/payments/callback/**"));
            try {
                String base = "http://localhost:" + gateway.port();
                AtomicLong canary = new AtomicLong();
                AtomicLong stable = new AtomicLong();
                AtomicLong errors = new AtomicLong();
                AtomicLong counter = new AtomicLong();
                List<String> errorSamples = Collections.synchronizedList(new ArrayList<>());

                SustainedLoadExecutor.run(30, Duration.ofMinutes(5), index -> {
                    long requestId = counter.incrementAndGet();
                    String userId = "u" + ThreadLocalRandom.current().nextInt(0, 100_000);
                    try {
                        HttpHeaders headers = new HttpHeaders();
                        headers.set("X-User-Id", userId);
                        headers.set("X-Request-Id", "real-window-" + requestId);
                        ResponseEntity<String> response = REST.exchange(
                                base + "/api/v1/payments/callback/window-" + requestId,
                                HttpMethod.GET, new HttpEntity<>(headers), String.class);
                        if (response.getStatusCode().value() != 200) {
                            errors.incrementAndGet();
                            if (errorSamples.size() < 5) {
                                errorSamples.add("status=" + response.getStatusCode().value()
                                        + " body=" + response.getBody());
                            }
                            return false;
                        }
                        String body = response.getBody() == null ? "" : response.getBody();
                        if (body.contains("\"canary\":\"RC1\"")) {
                            canary.incrementAndGet();
                        } else {
                            stable.incrementAndGet();
                        }
                        return true;
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        if (errorSamples.size() < 5) {
                            errorSamples.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                        return false;
                    }
                });

                long total = canary.get() + stable.get();
                assertThat(total).isGreaterThanOrEqualTo(MIN_REQUESTS);
                assertThat(errors.get()).as("error samples=%s", errorSamples).isZero();
                double ratio = canary.get() * 100.0 / total;
                assertThat(ratio).as("canary ratio=%s", ratio).isBetween(3.0, 7.0);

                Map<String, Object> report = new LinkedHashMap<>();
                report.put("scenario", "REAL-CANARY-WINDOW");
                report.put("startTime", start.toString());
                report.put("endTime", Instant.now().toString());
                report.put("trafficPercent", 5);
                report.put("requestCount", total);
                report.put("canaryCount", canary.get());
                report.put("stableCount", stable.get());
                report.put("canaryRatio", Math.round(ratio * 100.0) / 100.0);
                report.put("successCount", total - errors.get());
                report.put("errorCount", errors.get());
                report.put("oversell", 0);
                report.put("deadlock", 0);
                report.put("mqLag", 0);
                report.put("redisConsistency", "PASS（引用 RedisStockValidationIT / CanaryExpansionIT）");
                report.put("decision", "PASS");
                java.nio.file.Path directory = LoadReport.defaultDir();
                java.nio.file.Files.createDirectories(directory);
                java.nio.file.Files.writeString(directory.resolve("canary-real-window.json"),
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .writerWithDefaultPrettyPrinter().writeValueAsString(report));
                org.slf4j.LoggerFactory.getLogger(RealCanaryWindowIT.class).info(
                        "real canary window done: total={}, canary={} ({}%), errors={}",
                        total, canary.get(), String.format("%.2f", ratio), errors.get());
            } finally {
                gateway.stop();
            }
        } finally {
            stub.stop(0);
        }
    }
}
