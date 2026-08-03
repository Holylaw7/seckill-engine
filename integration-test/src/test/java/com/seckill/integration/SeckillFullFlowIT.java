package com.seckill.integration;

import com.seckill.auth.AuthApplication;
import com.seckill.auth.dto.LoginRequest;
import com.seckill.auth.dto.LoginResponse;
import com.seckill.common.result.Result;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.inventory.InventoryApplication;
import com.seckill.order.OrderApplication;
import com.seckill.payment.PaymentApplication;
import com.seckill.payment.dto.CreatePayRequest;
import com.seckill.payment.dto.CreatePayResponse;
import com.seckill.seckill.SeckillApplication;
import com.seckill.seckill.dto.ExecuteRequest;
import com.seckill.seckill.dto.ExecuteResponse;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 正向秒杀全链路（真实中间件）：
 * 登录 → 秒杀(Lua) → 事务消息 → 建单 → inventory DEDUCT → 支付创建。
 */
@Tag("integration")
class SeckillFullFlowIT extends IntegrationTestBase {

    private static final String SECRET = "seckill-engine-dev-secret-change-me";
    private static final RestTemplate REST = new RestTemplate();

    private static ServiceLauncher.RunningService AUTH;
    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService ORDER;
    private static ServiceLauncher.RunningService INVENTORY;
    private static ServiceLauncher.RunningService PAYMENT;

    @BeforeAll
    static void startServices() throws Exception {
        // Topic 预热（自定义 tag，避免被业务消费组投递）
        sendRocketMqMessage("seckill-order-tx", "TEST_WARMUP", "{\"warmup\":true}");
        seedAuthUser();

        AUTH = ServiceLauncher.start(AuthApplication.class, "auth", serviceArgs("seckill_auth", "auth-service",
                "--seckill.auth.jwt.secret=" + SECRET));
        SECKILL = ServiceLauncher.start(SeckillApplication.class, "seckill", serviceArgs("seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false",
                "--seckill.core.risk-check.base-url=http://localhost:" + AUTH.port()));
        ORDER = ServiceLauncher.start(OrderApplication.class, "order", serviceArgs("seckill_order", "order-service",
                "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port()));
        INVENTORY = ServiceLauncher.start(InventoryApplication.class, "inventory", serviceArgs("seckill_inventory", "inventory-service",
                "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port()));
        PAYMENT = ServiceLauncher.start(PaymentApplication.class, "payment", serviceArgs("seckill_payment", "payment-service"));
    }

    @AfterAll
    static void stopServices() {
        ServiceLauncher.RunningService[] services = {PAYMENT, INVENTORY, ORDER, SECKILL, AUTH};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
    }

    @BeforeEach
    void resetState() {
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
        redisSet("seckill:stock:20001", "1000");
        redisSet("seckill:stock:total:20001", "1000");
    }

    private static List<String> serviceArgs(String schema, String appName, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.application.name=" + appName,
                "--spring.datasource.url=" + jdbcUrl(schema),
                "--spring.datasource.username=test",
                "--spring.datasource.password=test",
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--rocketmq.name-server=" + ROCKETMQ.getHost() + ":" + ROCKETMQ.getMappedPort(9876),
                // classpath 同名 application.yml 仅加载第一个（gateway），
                // producer group 等专属配置统一命令行补齐
                "--rocketmq.producer.group=integration-" + appName,
                // integration-test classpath 携带 gateway 模块（WebFlux）；
                // 各业务服务为 Servlet 应用，排除 Gateway 全套自动配置（端到端不经 gateway 转发）
                "--spring.autoconfigure.exclude="
                        + "org.springframework.cloud.gateway.config.GatewayAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayMetricsAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayLoadBalancerClientAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayNoLoadBalancerClientAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.HttpClientAutoConfiguration"));
        args.addAll(List.of(extra));
        return args;
    }

    private static void seedAuthUser() throws Exception {
        String hash = new BCryptPasswordEncoder().encode("Test@123");
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO seckill_auth.`user` (id, username, password_hash, status, roles) "
                             + "VALUES (?, ?, ?, 1, 'USER')")) {
            statement.setLong(1, 10001L);
            statement.setString(2, "tester");
            statement.setString(3, hash);
            statement.executeUpdate();
        }
    }

    @Test
    void should_complete_full_seckill_flow() throws Exception {
        // ==================== Step 1 登录 ====================
        Result<LoginResponse> login = post(
                "http://localhost:" + AUTH.port() + "/api/v1/auth/login",
                new LoginRequest("tester", "Test@123"),
                new ParameterizedTypeReference<Result<LoginResponse>>() {
                });
        assertThat(login.getCode()).isZero();
        assertThat(login.getData().getToken()).isNotBlank();

        // session 落 Redis：userId / username / tokenVersion
        try (StatefulRedisConnection<String, String> connection = redisConnection()) {
            Map<String, String> session = connection.sync().hgetall("auth:session:10001");
            assertThat(session.get("userId")).isEqualTo("10001");
            assertThat(session.get("username")).isEqualTo("tester");
            assertThat(session.get("tokenVersion")).isEqualTo("1");
        }

        // ==================== Step 2 秒杀请求 ====================
        ExecuteRequest executeRequest = new ExecuteRequest();
        executeRequest.setSessionId(30001L);
        executeRequest.setSkuId(20001L);
        executeRequest.setQuantity(1);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", "10001");
        headers.set("X-Trace-Id", "test-flow-001");
        headers.setContentType(MediaType.APPLICATION_JSON);
        Result<ExecuteResponse> execute = REST.exchange(
                "http://localhost:" + SECKILL.port() + "/api/v1/seckill/execute",
                HttpMethod.POST, new HttpEntity<>(executeRequest, headers),
                new ParameterizedTypeReference<Result<ExecuteResponse>>() {
                }).getBody();
        assertThat(execute.getCode()).isZero();
        assertThat(execute.getData().getOrderId()).isNotBlank();
        String orderId = execute.getData().getOrderId();

        // Redis：库存 1000 → 999，用户购买标记创建
        assertThat(redisGet("seckill:stock:20001")).isEqualTo("999");
        assertThat(redisExists("seckill:user:20001:10001")).isEqualTo(1L);

        // ==================== Step 3 MQ 事务消息 → 建单 ====================
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(queryString(
                    "SELECT order_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                    .isEqualTo("WAIT_PAY");
            assertThat(queryInt(
                    "SELECT COUNT(*) FROM seckill_order.order_item WHERE order_id=" + orderId))
                    .isEqualTo(1);
            assertThat(queryString(
                    "SELECT deduct_status FROM seckill_seckill.seckill_pre_deduct WHERE order_id='" + orderId + "'"))
                    .isEqualTo("CONFIRMED");
        });

        // ==================== Step 4 inventory DEDUCT ====================
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(queryInt(
                    "SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=20001"))
                    .isEqualTo(999);
            assertThat(queryInt(
                    "SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=20001"))
                    .isEqualTo(1);
            assertThat(queryInt(
                    "SELECT COUNT(*) FROM seckill_inventory.stock_flow "
                            + "WHERE sku_id=20001 AND change_type='DEDUCT'"))
                    .isEqualTo(1);
        });

        // ==================== Step 5 order active_key ====================
        assertThat(queryString(
                "SELECT active_key FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                .isEqualTo("10001:30001:20001");

        // ==================== Step 6 创建支付 ====================
        CreatePayRequest payRequest = new CreatePayRequest();
        payRequest.setOrderNo(orderId);
        payRequest.setUserId(10001L);
        payRequest.setAmount(new BigDecimal("99.00"));
        payRequest.setChannel("MOCK");
        Result<CreatePayResponse> pay = post(
                "http://localhost:" + PAYMENT.port() + "/api/v1/payments/create",
                payRequest,
                new ParameterizedTypeReference<Result<CreatePayResponse>>() {
                });
        assertThat(pay.getCode()).isZero();
        assertThat(pay.getData().getPaymentNo()).isNotBlank();
        assertThat(queryString(
                "SELECT status FROM seckill_payment.payment_order WHERE order_no='" + orderId + "'"))
                .isEqualTo("WAIT_PAY");

        // 一致性：Redis 与 MySQL 库存口径
        assertThat(queryInt(
                "SELECT total_stock FROM seckill_inventory.inventory WHERE sku_id=20001"))
                .isEqualTo(1000);
    }

    private static <T> Result<T> post(String url, Object body, ParameterizedTypeReference<Result<T>> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return REST.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), type).getBody();
    }
}
