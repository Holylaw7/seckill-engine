package com.seckill.integration.load;

import com.seckill.auth.AuthApplication;
import com.seckill.auth.dto.LoginResponse;
import com.seckill.common.result.Result;
import com.seckill.gateway.GatewayApplication;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.seckill.SeckillApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.3 限流正确性回归：
 * apiKeyResolver 单键 + replenishRate=1 / burstCapacity=100，5000 请求快速发送，
 * 断言放行数 ∈ [burst, burst+slack]、拒绝均为 429、无漏放/无误杀。
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class GatewayRateLimitRegressionTest extends IntegrationTestBase {

    private static final int TOTAL = 3000;
    private static final int CONCURRENCY = 100;
    private static final int BURST = 100;
    private static final int REPLENISH = 1;
    private static final String PASSWORD = "Test@123";
    private static final String JWT_SECRET = "seckill-engine-dev-secret-change-me";

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 6_300_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long USER_ID = 9_100_000_000L + (RUN_ID % 100_000L) * 1000L;

    private static ServiceLauncher.RunningService AUTH;
    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService GATEWAY;
    private static String gatewayBaseUrl;

    @BeforeAll
    static void startServices() throws Exception {
        TestDataHelper.seedSeckill(SESSION_ID, SKU_ID, 1, "READY", "99.00");
        TestDataHelper.resetInventory(SKU_ID, 1, 1, 0);
        AUTH = ServiceSupport.start(AuthApplication.class, "auth", "seckill_auth", "auth-service",
                "--seckill.auth.jwt.secret=" + JWT_SECRET);
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false");
        GATEWAY = ServiceLauncher.start(GatewayApplication.class, "gateway", gatewayArgs());
        gatewayBaseUrl = "http://localhost:" + GATEWAY.port();
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        execute("DELETE FROM seckill_auth.`user` WHERE id=" + USER_ID);
        execute("INSERT INTO seckill_auth.`user` (id, username, password_hash, status, roles) VALUES ("
                + USER_ID + ",'ratelimituser" + USER_ID + "','" + hash + "',1,'USER')");
        redisSet("seckill:stock:" + SKU_ID, "1");
        redisSet("seckill:stock:total:" + SKU_ID, "1");
    }

    @AfterAll
    static void tearDown() throws Exception {
        ServiceLauncher.RunningService[] services = {GATEWAY, SECKILL, AUTH};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
        cleanupNamespace();
    }

    @Test
    void rateLimitShouldAllowExactBurstAndRejectRest() throws Exception {
        Result<LoginResponse> login = TestHttp.login(gatewayBaseUrl, "ratelimituser" + USER_ID, PASSWORD);
        assertThat(login).isNotNull();
        assertThat(login.getCode()).isZero();
        String token = login.getData().getToken();

        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger denied429 = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        AtomicInteger transportErrors = new AtomicInteger();
        LoadTestExecutor.run(new LoadConfig(CONCURRENCY, TOTAL, Duration.ofMinutes(5)),
                index -> {
                    ResponseEntity<String> response;
                    try {
                        response = TestHttp.executeRawNoError(
                                gatewayBaseUrl, USER_ID, SESSION_ID, SKU_ID, 1,
                                "ratelimit-" + RUN_ID + "-" + index, token);
                    } catch (Exception e) {
                        transportErrors.incrementAndGet();
                        if (transportErrors.get() <= 5) {
                            log().warn("rate limit transport error: {}", e.toString());
                        }
                        return false;
                    }
                    int status = response.getStatusCode().value();
                    if (status == 200) {
                        allowed.incrementAndGet();
                    } else if (status == 429) {
                        denied429.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                    return status == 200;
                });

        int allowedCount = allowed.get();
        int deniedCount = denied429.get();
        // 放行：burst 内全部放行（无误杀），快速发送期间仅少量 replenish 补偿
        assertThat(allowedCount).isGreaterThanOrEqualTo(BURST);
        assertThat(allowedCount).isLessThanOrEqualTo(BURST + 30);
        // 拒绝：全部为 429（无其他错误码）
        assertThat(other.get()).isZero();
        // 传输层异常必须为 0（环境健康性）
        assertThat(transportErrors.get()).isZero();
        assertThat(allowedCount + deniedCount).isEqualTo(TOTAL);
        log().info("GATEWAY_RATELIMIT: total={}, allowed={}, denied429={}, other={}, transportErrors={}",
                TOTAL, allowedCount, deniedCount, other.get(), transportErrors.get());
    }

    private static org.slf4j.Logger log() {
        return org.slf4j.LoggerFactory.getLogger(GatewayRateLimitRegressionTest.class);
    }

    private static java.util.List<String> gatewayArgs() {
        return java.util.List.of(
                "--server.port=0",
                "--spring.application.name=gateway",
                "--spring.main.web-application-type=reactive",
                "--spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
                "--spring.data.redis.host=" + redisHost(),
                "--spring.data.redis.port=" + redisPort(),
                "--seckill.gateway.jwt.secret=" + JWT_SECRET,
                "--spring.cloud.gateway.routes[0].id=auth-service",
                "--spring.cloud.gateway.routes[0].uri=http://localhost:" + AUTH.port(),
                "--spring.cloud.gateway.routes[0].predicates[0]=Path=/api/v1/auth/**",
                "--spring.cloud.gateway.routes[1].id=seckill-service",
                "--spring.cloud.gateway.routes[1].uri=http://localhost:" + SECKILL.port(),
                "--spring.cloud.gateway.routes[1].predicates[0]=Path=/api/v1/seckill/**",
                "--spring.cloud.gateway.routes[1].filters[0].name=RequestRateLimiter",
                "--spring.cloud.gateway.routes[1].filters[0].args.key-resolver=#{@apiKeyResolver}",
                "--spring.cloud.gateway.routes[1].filters[0].args.redis-rate-limiter.replenishRate=" + REPLENISH,
                "--spring.cloud.gateway.routes[1].filters[0].args.redis-rate-limiter.burstCapacity=" + BURST,
                "--spring.cloud.gateway.routes[2].id=order-service",
                "--spring.cloud.gateway.routes[2].uri=http://localhost:1",
                "--spring.cloud.gateway.routes[2].predicates[0]=Path=/api/v1/orders/**",
                "--spring.cloud.gateway.routes[3].id=payment-service",
                "--spring.cloud.gateway.routes[3].uri=http://localhost:1",
                "--spring.cloud.gateway.routes[3].predicates[0]=Path=/api/v1/payments/**");
    }

    private static void cleanupNamespace() throws Exception {
        execute("DELETE FROM seckill_order.idempotent WHERE user_id=" + USER_ID);
        execute("DELETE FROM seckill_order.seckill_order WHERE user_id=" + USER_ID);
        execute("DELETE FROM seckill_seckill.seckill_pre_deduct WHERE user_id=" + USER_ID);
        execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_session WHERE id=" + SESSION_ID);
        execute("DELETE FROM seckill_auth.`user` WHERE id=" + USER_ID);
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
    }
}
