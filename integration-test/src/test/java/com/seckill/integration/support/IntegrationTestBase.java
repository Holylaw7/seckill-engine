package com.seckill.integration.support;

import com.seckill.test.support.AbstractIntegrationTest;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 5.3 集成测试基类：
 * <ul>
 *   <li>复用 test-support 容器（MySQL 8.0.36 / Redis 7.2.4 / RocketMQ 5.1.4）</li>
 *   <li>初始化 5 个服务 schema（按依赖顺序执行仓库级 V1.0 脚本 + 测试种子）</li>
 *   <li>提供 JDBC / Redis / RocketMQ 真实访问工具</li>
 * </ul>
 */
public abstract class IntegrationTestBase extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(IntegrationTestBase.class);

    public static final List<String> SCHEMAS = List.of(
            "seckill_auth", "seckill_seckill", "seckill_inventory", "seckill_order", "seckill_payment");

    private static final List<String> SCHEMA_SCRIPTS = List.of(
            "sql/auth-service/V1.0__init.sql",
            "sql/seckill-service/V1.0__init.sql",
            "sql/inventory-service/V1.0__init.sql",
            "sql/inventory-service/V2__inventory_bucket.sql",
            "sql/inventory-service/V3__stock_flow_bucket.sql",
            "sql/order-service/V1.0__init.sql",
            "sql/payment-service/V1.0__init.sql",
            "sql/test-data/seckill-test.sql",
            "sql/test-data/inventory-test.sql");

    static {
        initDatabases();
        ensureRocketMqTopic();
    }

    private static void initDatabases() {
        // 建库/建表需要 root 权限（test 用户仅拥有默认库权限）
        try (Connection connection = DriverManager.getConnection(
                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                        + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "root", MYSQL.getPassword())) {
            for (String script : SCHEMA_SCRIPTS) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(script));
            }
            // 授权 test 用户访问全部服务库（业务服务上下文使用 test/test 连接）
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("GRANT ALL PRIVILEGES ON *.* TO 'test'@'%'");
                statement.execute("FLUSH PRIVILEGES");
            }
        } catch (Exception e) {
            throw new IllegalStateException("integration database init failed", e);
        }
    }

    /**
     * MySQL 容器重启后幂等重建 5 个服务 schema（仅 V1.0 建库建表脚本）并恢复 test 用户全局授权。
     * 不重放 test-data 种子，避免唯一键重复。
     */
    protected static void restoreMysqlSchemas() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                        + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "root", MYSQL.getPassword())) {
            for (String script : List.of(
            "sql/auth-service/V1.0__init.sql",
            "sql/seckill-service/V1.0__init.sql",
            "sql/inventory-service/V1.0__init.sql",
            "sql/inventory-service/V2__inventory_bucket.sql",
            "sql/inventory-service/V3__stock_flow_bucket.sql",
            "sql/order-service/V1.0__init.sql",
                    "sql/payment-service/V1.0__init.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(script));
            }
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("GRANT ALL PRIVILEGES ON *.* TO 'test'@'%'");
                statement.execute("FLUSH PRIVILEGES");
            }
        }
    }

    /**
     * 显式创建冻结 Topic（seckill-order-tx）：
     * 绕过 broker autoCreateTopicEnable 开关差异，幂等可重复执行。
     */
    protected static void ensureRocketMqTopic() {
        try {
            int brokerPort = Integer.getInteger("testcontainers.rocketmq.broker-port", 20911);
            ExecResult result = ROCKETMQ.execInContainer(
                    "sh", "-c",
                    "sh mqadmin updateTopic -n 127.0.0.1:9876 -b 127.0.0.1:" + brokerPort + " "
                            + "-t seckill-order-tx");
            if (result.getExitCode() != 0) {
                log.warn("rocketmq topic ensure exit={}, stdout={}, stderr={}",
                        result.getExitCode(), result.getStdout(), result.getStderr());
            } else {
                log.info("rocketmq topic ensured: {}", result.getStdout());
            }
        } catch (Exception e) {
            log.warn("rocketmq topic ensure failed, fallback to send retry", e);
        }
    }

    // ==================== MySQL ====================

    protected static Connection openConnection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    protected static void execute(String sql) throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * 以 root 身份执行权限类 SQL（REVOKE/GRANT/FLUSH PRIVILEGES），供故障注入使用。
     */
    protected static void executeAsRoot(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                        + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * 断开 test 用户当前所有连接（配合 ACCOUNT LOCK 模拟数据库连接异常）。
     */
    protected static void killTestConnections() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                        + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "root", MYSQL.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT id FROM information_schema.processlist "
                             + "WHERE user='test' AND id <> CONNECTION_ID()")) {
            List<String> ids = new ArrayList<>();
            while (resultSet.next()) {
                ids.add(resultSet.getString(1));
            }
            for (String id : ids) {
                try (Statement kill = connection.createStatement()) {
                    kill.execute("KILL " + id);
                } catch (Exception ignored) {
                    // 连接可能已关闭
                }
            }
        }
    }

    /**
     * 准备写失败注入专用账号（幂等）：
     * order_fault 无 seckill_order.INSERT、inventory_fault 无 seckill_inventory.INSERT，
     * 其余权限正常，保证 SELECT/连接可用、INSERT 被确定性拒绝。
     */
    protected static void prepareFaultUsers() throws Exception {
        executeAsRoot("CREATE USER IF NOT EXISTS 'order_fault'@'%' IDENTIFIED BY 'test'");
        executeAsRoot("CREATE USER IF NOT EXISTS 'inventory_fault'@'%' IDENTIFIED BY 'test'");
        executeAsRoot("GRANT SELECT ON *.* TO 'order_fault'@'%'");
        executeAsRoot("GRANT SELECT ON *.* TO 'inventory_fault'@'%'");
        executeAsRoot("GRANT INSERT,UPDATE,DELETE ON seckill_order.* TO 'order_fault'@'%'");
        executeAsRoot("GRANT INSERT,UPDATE,DELETE ON seckill_inventory.* TO 'inventory_fault'@'%'");
    }

    protected static String queryString(String sql) throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    protected static int queryInt(String sql) throws Exception {
        String value = queryString(sql);
        return value == null ? -1 : Integer.parseInt(value);
    }

    protected static String jdbcUrl(String schema) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + schema
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    }

    // ==================== Redis ====================

    protected static void cleanRedis(String... patterns) {
        try (StatefulRedisConnection<String, String> connection = RedisClient.create(redisUri()).connect()) {
            RedisCommands<String, String> commands = connection.sync();
            for (String pattern : patterns) {
                List<String> keys = new ArrayList<>();
                io.lettuce.core.KeyScanCursor<String> cursor =
                        commands.scan(ScanArgs.Builder.matches(pattern).limit(1000));
                while (true) {
                    keys.addAll(cursor.getKeys());
                    if (cursor.isFinished()) {
                        break;
                    }
                    cursor = commands.scan(cursor, ScanArgs.Builder.matches(pattern).limit(1000));
                }
                if (!keys.isEmpty()) {
                    commands.del(keys.toArray(String[]::new));
                }
            }
        }
    }

    protected static long countRedisKeys(String pattern) {
        try (StatefulRedisConnection<String, String> connection = RedisClient.create(redisUri()).connect()) {
            RedisCommands<String, String> commands = connection.sync();
            long count = 0;
            io.lettuce.core.KeyScanCursor<String> cursor =
                    commands.scan(ScanArgs.Builder.matches(pattern).limit(1000));
            while (true) {
                count += cursor.getKeys().size();
                if (cursor.isFinished()) {
                    return count;
                }
                cursor = commands.scan(cursor, ScanArgs.Builder.matches(pattern).limit(1000));
            }
        }
    }

    protected static void redisSet(String key, String value) {
        try (StatefulRedisConnection<String, String> connection = RedisClient.create(redisUri()).connect()) {
            connection.sync().set(key, value);
        }
    }

    protected static String redisGet(String key) {
        try (StatefulRedisConnection<String, String> connection = RedisClient.create(redisUri()).connect()) {
            return connection.sync().get(key);
        }
    }

    protected static long redisExists(String key) {
        try (StatefulRedisConnection<String, String> connection = RedisClient.create(redisUri()).connect()) {
            return connection.sync().exists(key);
        }
    }

    protected static String redisPing() {
        try (StatefulRedisConnection<String, String> connection = RedisClient.create(redisUri()).connect()) {
            return connection.sync().ping();
        }
    }

    protected static StatefulRedisConnection<String, String> redisConnection() {
        return RedisClient.create(redisUri()).connect();
    }

    private static String redisUri() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    // ==================== 测试服务支撑 ====================

    protected static String redisHost() {
        return REDIS.getHost();
    }

    protected static int redisPort() {
        return REDIS.getMappedPort(6379);
    }

    protected static String rocketMqNameServer() {
        return ROCKETMQ.getHost() + ":" + ROCKETMQ.getMappedPort(9876);
    }

    // ==================== 故障演练恢复探测 ====================

    protected static void awaitRedisReady(Duration timeout) {
        await().atMost(timeout).until(() -> {
            try {
                return "PONG".equals(redisPing());
            } catch (Exception e) {
                return false;
            }
        });
    }

    protected static void awaitMysqlReady(Duration timeout) {
        await().atMost(timeout).until(() -> {
            try {
                return queryInt("SELECT 1") == 1;
            } catch (Exception e) {
                return false;
            }
        });
    }

    protected static void awaitRocketMqReady(Duration timeout) {
        await().atMost(timeout).until(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ROCKETMQ.getHost(), ROCKETMQ.getMappedPort(9876)), 3000);
                return true;
            } catch (Exception e) {
                return false;
            }
        });
    }

    // ==================== RocketMQ ====================

    protected static void sendRocketMqMessage(String topic, String tag, String body) throws Exception {
        // broker 注册 namesrv 存在延迟：Topic 路由未就绪时等待重试（禁止固定 sleep）
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> trySend(topic, tag, body));
    }

    private static boolean trySend(String topic, String tag, String body) {
        DefaultMQProducer producer = new DefaultMQProducer("integration-test-producer");
        producer.setNamesrvAddr(ROCKETMQ.getHost() + ":" + ROCKETMQ.getMappedPort(9876));
        producer.setSendMsgTimeout(10_000);
        try {
            producer.start();
            Message message = new Message(topic, tag, body.getBytes(StandardCharsets.UTF_8));
            SendResult result = producer.send(message);
            return result.getSendStatus() == SendStatus.SEND_OK;
        } catch (Exception e) {
            return false;
        } finally {
            producer.shutdown();
        }
    }
}
