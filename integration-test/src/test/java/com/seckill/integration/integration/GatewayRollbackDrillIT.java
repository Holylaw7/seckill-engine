package com.seckill.integration.integration;

import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.TestHttp;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.6.7 Gateway 配置回滚演练：
 * Phase 6.3 容量调优配置（新）→ 默认配置（旧）切换后，流量恢复且响应一致。
 * 路由指向本地 stub 后端，避免依赖完整业务拓扑。
 */
@Tag("integration")
class GatewayRollbackDrillIT extends IntegrationTestBase {

    private static final String ROUTE_PATH = "/api/v1/payments/callback/**";

    @Test
    void gatewayConfigRollbackShouldRestoreTraffic() throws Exception {
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            byte[] body = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        stub.start();
        int stubPort = stub.getAddress().getPort();
        try {
            // 新配置：Phase 6.3 容量调优（Netty / HttpClient 连接池）
            ServiceLauncher.RunningService gatewayNew =
                    startGateway(stubPort, newCapacityArgs());
            try {
                assertThat(TestHttp.getRaw("http://localhost:" + gatewayNew.port()
                        + "/api/v1/payments/callback/drill-new")).isEqualTo("{\"code\":0}");
            } finally {
                gatewayNew.stop();
            }

            // 旧配置：默认参数（回滚路径），流量恢复
            ServiceLauncher.RunningService gatewayOld =
                    startGateway(stubPort, List.of());
            try {
                assertThat(TestHttp.getRaw("http://localhost:" + gatewayOld.port()
                        + "/api/v1/payments/callback/drill-old")).isEqualTo("{\"code\":0}");
            } finally {
                gatewayOld.stop();
            }
        } finally {
            stub.stop(0);
        }
    }

    private static ServiceLauncher.RunningService startGateway(int stubPort, List<String> extras) {
        List<String> args = new ArrayList<>(List.of(
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
                "--spring.cloud.gateway.routes[0].id=drill-stub",
                "--spring.cloud.gateway.routes[0].uri=http://127.0.0.1:" + stubPort,
                "--spring.cloud.gateway.routes[0].predicates[0]=Path=" + ROUTE_PATH));
        args.addAll(extras);
        return ServiceLauncher.start(com.seckill.gateway.GatewayApplication.class, "gateway", args);
    }

    private static List<String> newCapacityArgs() {
        return List.of(
                "--server.netty.max-connections=20000",
                "--server.netty.connection-timeout=5000",
                "--spring.cloud.gateway.httpclient.pool.type=fixed",
                "--spring.cloud.gateway.httpclient.pool.max-connections=2000",
                "--spring.cloud.gateway.httpclient.pool.max-pending-acquire=10000",
                "--spring.cloud.gateway.httpclient.pool.acquire-timeout=10000",
                "--spring.cloud.gateway.httpclient.connect-timeout=5000",
                "--spring.cloud.gateway.httpclient.response-timeout=30s");
    }
}
