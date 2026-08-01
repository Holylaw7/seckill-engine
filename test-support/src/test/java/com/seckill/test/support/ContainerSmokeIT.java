package com.seckill.test.support;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试基础设施冒烟验证（*IT 默认不随 mvn test 执行，手动/CI 触发）。
 */
@Tag("integration")
class ContainerSmokeIT extends AbstractIntegrationTest {

    @Test
    void mysqlShouldAcceptConnectionAndQuery() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
    }

    @Test
    void redisShouldPing() {
        try (StatefulRedisConnection<String, String> connection = RedisClient.create(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379)).connect()) {
            assertEquals("PONG", connection.sync().ping());
        }
    }

    @Test
    void rocketmqNameserverShouldAcceptConnection() throws Exception {
        String namesrvAddr = ROCKETMQ.getHost() + ":" + ROCKETMQ.getMappedPort(9876);
        String host = namesrvAddr.split(":")[0];
        int port = Integer.parseInt(namesrvAddr.split(":")[1]);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            assertTrue(socket.isConnected());
        }
    }
}
