package com.seckill.integration.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务上下文启动辅助：与 SeckillFullFlowIT 相同的最小启动参数（随机端口、容器中间件、隔离配置）。
 */
public final class ServiceSupport {

    private static final String GATEWAY_EXCLUDES =
            "org.springframework.cloud.gateway.config.GatewayAutoConfiguration,"
                    + "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                    + "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration,"
                    + "org.springframework.cloud.gateway.config.GatewayMetricsAutoConfiguration,"
                    + "org.springframework.cloud.gateway.config.GatewayLoadBalancerClientAutoConfiguration,"
                    + "org.springframework.cloud.gateway.config.GatewayNoLoadBalancerClientAutoConfiguration,"
                    + "org.springframework.cloud.gateway.config.HttpClientAutoConfiguration";

    private ServiceSupport() {
    }

    public static ServiceLauncher.RunningService start(Class<?> applicationClass, String name,
                                                       String schema, String appName, String... extra) {
        return ServiceLauncher.start(applicationClass, name, serviceArgs(schema, appName, extra));
    }

    public static List<String> serviceArgs(String schema, String appName, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.application.name=" + appName,
                "--spring.datasource.url=" + IntegrationTestBase.jdbcUrl(schema),
                "--spring.datasource.username=test",
                "--spring.datasource.password=test",
                "--spring.data.redis.host=" + IntegrationTestBase.redisHost(),
                "--spring.data.redis.port=" + IntegrationTestBase.redisPort(),
                "--rocketmq.name-server=" + IntegrationTestBase.rocketMqNameServer(),
                "--rocketmq.producer.group=integration-" + appName,
                "--spring.autoconfigure.exclude=" + GATEWAY_EXCLUDES));
        args.addAll(List.of(extra));
        return args;
    }
}
