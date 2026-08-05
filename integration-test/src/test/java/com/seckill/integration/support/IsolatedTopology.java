package com.seckill.integration.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Phase 6.4 生产拓扑隔离：
 * 将 Gateway / auth / seckill / order / inventory 各自启动为独立 JVM（复用 surefire 测试类路径），
 * MySQL / Redis / RocketMQ 保持独立 Testcontainers 容器，消除共享 JVM 环境噪声。
 */
public final class IsolatedTopology {

    private static final Logger log = LoggerFactory.getLogger(IsolatedTopology.class);

    public static final int GATEWAY_PORT = 18080;
    public static final int AUTH_PORT = 18081;
    public static final int SECKILL_PORT = 18082;
    public static final int ORDER_PORT = 18083;
    public static final int INVENTORY_PORT = 18085;

    private static final String JWT_SECRET = "seckill-engine-dev-secret-change-me";
    private static final String GATEWAY_EXCLUDES =
            "org.springframework.cloud.gateway.config.GatewayAutoConfiguration,"
                    + "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                    + "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration,"
                    + "org.springframework.cloud.gateway.config.GatewayMetricsAutoConfiguration,"
                    + "org.springframework.cloud.gateway.config.GatewayLoadBalancerClientAutoConfiguration,"
                    + "org.springframework.cloud.gateway.config.GatewayNoLoadBalancerClientAutoConfiguration,"
                    + "org.springframework.cloud.gateway.config.HttpClientAutoConfiguration";

    private IsolatedTopology() {
    }

    public record RunningProcess(Process process, int port, String name, Path logFile) {
        public void stop() {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    public static Map<String, RunningProcess> startAll() throws Exception {
        Path logDir = Path.of("target", "isolated-topology");
        Files.createDirectories(logDir);
        Map<String, RunningProcess> running = new LinkedHashMap<>();
        try {
            startService("auth", "com.seckill.auth.AuthApplication", "seckill_auth", "auth-service",
                    AUTH_PORT, List.of("--seckill.auth.jwt.secret=" + JWT_SECRET), logDir, running);
            startService("seckill", "com.seckill.seckill.SeckillApplication", "seckill_seckill", "seckill-service",
                    SECKILL_PORT, List.of(
                            "--seckill.core.risk-check.enabled=false",
                            "--server.tomcat.threads.max=1000",
                            "--server.tomcat.accept-count=20000"), logDir, running);
            startService("order", "com.seckill.order.OrderApplication", "seckill_order", "order-service",
                    ORDER_PORT, List.of(
                            "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL_PORT,
                            "--seckill.order.timeout-close.period-seconds=3600000",
                            "--seckill.order.timeout-close.batch-size=10000",
                            "--seckill.order.cancel-notify-compensate-period-seconds=3600000"), logDir, running);
            startService("inventory", "com.seckill.inventory.InventoryApplication", "seckill_inventory", "inventory-service",
                    INVENTORY_PORT, List.of(
                            "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL_PORT), logDir, running);
            startGateway(logDir, running);
            for (RunningProcess process : running.values()) {
                awaitPort(process.port(), Duration.ofSeconds(150));
                log.info("isolated topology service ready: {}:{}", process.name(), process.port());
            }
            return running;
        } catch (Exception e) {
            running.values().forEach(RunningProcess::stop);
            throw e;
        }
    }

    public static void stopAll(Map<String, RunningProcess> running) {
        if (running == null) {
            return;
        }
        running.values().forEach(RunningProcess::stop);
    }

    private static void startService(String name, String mainClass, String schema, String appName,
                                     int port, List<String> extras, Path logDir,
                                     Map<String, RunningProcess> running) throws IOException {
        List<String> args = new ArrayList<>();
        args.add(javaBin());
        args.add("-Xmx1024m");
        args.add("-Xlog:gc:file=" + logDir.resolve(name + "-gc.log"));
        args.add("-cp");
        args.add(System.getProperty("java.class.path"));
        args.add(mainClass);
        args.add("--server.port=" + port);
        args.add("--spring.application.name=" + appName);
        args.add("--spring.datasource.url=" + IntegrationTestBase.jdbcUrl(schema));
        args.add("--spring.datasource.username=test");
        args.add("--spring.datasource.password=test");
        args.add("--spring.data.redis.host=" + IntegrationTestBase.redisHost());
        args.add("--spring.data.redis.port=" + IntegrationTestBase.redisPort());
        args.add("--rocketmq.name-server=" + IntegrationTestBase.rocketMqNameServer());
        args.add("--rocketmq.producer.group=isolated-" + appName);
        // Phase 6.6 内部接口 Service ACL：类路径同名 yml 不生效，隔离拓扑统一命令行补齐
        args.add("--seckill.internal-auth.enabled=true");
        args.add("--seckill.internal-auth.clients.order-service=dev-order-secret");
        args.add("--seckill.internal-auth.clients.inventory-service=dev-inventory-secret");
        args.add("--seckill.internal-auth.admin-secret=dev-admin-secret");
        args.add("--spring.autoconfigure.exclude=" + GATEWAY_EXCLUDES);
        args.addAll(extras);
        running.put(name, launch(name, port, args, logDir));
    }

    private static void startGateway(Path logDir, Map<String, RunningProcess> running) throws IOException {
        List<String> args = new ArrayList<>();
        args.add(javaBin());
        args.add("-Xmx1024m");
        args.add("-Xlog:gc:file=" + logDir.resolve("gateway-gc.log"));
        args.add("-cp");
        args.add(System.getProperty("java.class.path"));
        args.add("com.seckill.gateway.GatewayApplication");
        args.add("--server.port=" + GATEWAY_PORT);
        args.add("--spring.application.name=gateway");
        args.add("--spring.main.web-application-type=reactive");
        args.add("--spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration");
        args.add("--spring.data.redis.host=" + IntegrationTestBase.redisHost());
        args.add("--spring.data.redis.port=" + IntegrationTestBase.redisPort());
        args.add("--seckill.gateway.jwt.secret=" + JWT_SECRET);
        args.add("--spring.cloud.gateway.routes[0].id=auth-service");
        args.add("--spring.cloud.gateway.routes[0].uri=http://localhost:" + AUTH_PORT);
        args.add("--spring.cloud.gateway.routes[0].predicates[0]=Path=/api/v1/auth/**");
        args.add("--spring.cloud.gateway.routes[1].id=seckill-service");
        args.add("--spring.cloud.gateway.routes[1].uri=http://localhost:" + SECKILL_PORT);
        args.add("--spring.cloud.gateway.routes[1].predicates[0]=Path=/api/v1/seckill/**");
        args.add("--spring.cloud.gateway.routes[1].filters[0].name=RequestRateLimiter");
        args.add("--spring.cloud.gateway.routes[1].filters[0].args.key-resolver=#{@rateLimitKeyResolver}");
        args.add("--spring.cloud.gateway.routes[1].filters[0].args.redis-rate-limiter.replenishRate=100000");
        args.add("--spring.cloud.gateway.routes[1].filters[0].args.redis-rate-limiter.burstCapacity=200000");
        args.add("--spring.cloud.gateway.routes[2].id=order-service");
        args.add("--spring.cloud.gateway.routes[2].uri=http://localhost:" + ORDER_PORT);
        args.add("--spring.cloud.gateway.routes[2].predicates[0]=Path=/api/v1/orders/**");
        args.add("--spring.cloud.gateway.routes[3].id=payment-service");
        args.add("--spring.cloud.gateway.routes[3].uri=http://localhost:1");
        args.add("--spring.cloud.gateway.routes[3].predicates[0]=Path=/api/v1/payments/**");
        running.put("gateway", launch("gateway", GATEWAY_PORT, args, logDir));
    }

    private static RunningProcess launch(String name, int port, List<String> args, Path logDir)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectOutput(logDir.resolve(name + ".log").toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        log.info("isolated topology launched: {} port={} pid={}", name, port, process.pid());
        return new RunningProcess(process, port, name, logDir.resolve(name + ".log"));
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    }

    private static void awaitPort(int port, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                return;
            } catch (Exception ignored) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("service port not ready: " + port);
    }
}
