package com.seckill.test.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

/**
 * 集成测试公共基类（Phase 5.1 冻结）：
 * <ul>
 *   <li>MySQL 8.x</li>
 *   <li>Redis 7.x</li>
 *   <li>RocketMQ 5.x（可通过 -Dtestcontainers.rocketmq.enabled=false 跳过）</li>
 * </ul>
 * 容器为类级单例，动态注入 Spring 配置（datasource/redis/rocketmq）。
 */
public abstract class AbstractIntegrationTest {

    private static final boolean ROCKETMQ_ENABLED =
            Boolean.parseBoolean(System.getProperty("testcontainers.rocketmq.enabled", "true"));

    /** Phase 6.11：固定映射端口可配置（默认 19876/20911），端口缓存冲突时可用备用端口。 */
    private static final int ROCKETMQ_NAMESRV_PORT =
            Integer.getInteger("testcontainers.rocketmq.namesrv-port", 19876);
    private static final int ROCKETMQ_BROKER_PORT =
            Integer.getInteger("testcontainers.rocketmq.broker-port", 20911);

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("seckill_test")
            .withUsername("test")
            .withPassword("test")
            .withStartupTimeoutSeconds(180);

    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2.4")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(180));

    /**
     * RocketMQ 单容器（namesrv + broker）：
     * 固定映射（默认 19876/20911，可经系统属性覆盖；避开本机开发容器占用的 9876/10911），
     * broker 通告 127.0.0.1:20911（容器内端口），保证宿主机 producer/consumer 可直连。
     */
    protected static final GenericContainer<?> ROCKETMQ = new GenericContainer<>(
            DockerImageName.parse("apache/rocketmq:5.3.1"))
            .withExposedPorts(9876, 20911)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(ROCKETMQ_NAMESRV_PORT), new ExposedPort(9876)),
                    new PortBinding(Ports.Binding.bindPort(ROCKETMQ_BROKER_PORT), new ExposedPort(20911))))
            .withCommand("sh", "-c",
                    "printf 'brokerIP1=127.0.0.1\\nlistenPort=20911\\nautoCreateTopicEnable=true\\n' > /tmp/broker.conf; "
                            + "sh mqnamesrv & sleep 10; sh mqbroker -n 127.0.0.1:9876 -c /tmp/broker.conf & tail -f /dev/null")
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(300));

    static {
        MYSQL.start();
        REDIS.start();
        if (ROCKETMQ_ENABLED) {
            ROCKETMQ.start();
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        if (ROCKETMQ_ENABLED) {
            registry.add("rocketmq.name-server", () ->
                    ROCKETMQ.getHost() + ":" + ROCKETMQ.getMappedPort(9876));
        } else {
            registry.add("rocketmq.name-server", () -> "localhost:9876");
        }
    }
}
