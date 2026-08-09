package com.seckill.integration.load;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import java.time.Duration;

/**
 * Phase 6.14 Task 2：独立 RocketMQ 拓扑（namesrv 与 broker 各自独立容器）。
 * 用于容量验证，满足"禁止单容器 namesrv+broker"要求；
 * broker 通告 127.0.0.1:{映射端口}，宿主机 producer/consumer 可直连。
 */
public final class RocketMqIndependentTopology {

    private static final Logger log = LoggerFactory.getLogger(RocketMqIndependentTopology.class);
    private static final DockerImageName IMAGE = DockerImageName.parse("apache/rocketmq:5.3.1");

    private final Network network = Network.newNetwork();
    private final int namesrvPort;
    private final int brokerPort;
    private final GenericContainer<?> namesrv;
    private final GenericContainer<?> broker;

    public RocketMqIndependentTopology(int namesrvPort, int brokerPort) {
        this.namesrvPort = namesrvPort;
        this.brokerPort = brokerPort;
        this.namesrv = new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withNetworkAliases("seckill-namesrv")
                .withExposedPorts(9876)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(namesrvPort), new ExposedPort(9876))))
                .withCommand("sh", "-c", "sh mqnamesrv")
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofSeconds(180));
        this.broker = new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withNetworkAliases("seckill-broker")
                .withExposedPorts(brokerPort)
                .dependsOn(namesrv)
                .withEnv("JAVA_OPT_EXT", "-Xms512m -Xmx1024m -XX:+UseG1GC")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(brokerPort), new ExposedPort(brokerPort))))
                .withCommand("sh", "-c",
                        "printf 'brokerIP1=127.0.0.1\\nlistenPort=" + brokerPort
                                + "\\nbrokerName=seckill-broker\\nbrokerClusterName=DefaultCluster"
                                + "\\nnamesrvAddr=seckill-namesrv:9876\\nautoCreateTopicEnable=true"
                                + "\\nsendMessageThreadPoolNums=64\\npullMessageThreadPoolNums=64"
                                + "\\nstorePutMessageThreadPoolNums=32\\ndispatchMessageThreadPoolNums=32\\n' "
                                + "> /tmp/broker.conf; "
                                + "sh mqbroker -n seckill-namesrv:9876 -c /tmp/broker.conf")
                .waitingFor(Wait.forLogMessage(".*boot success.*|.*Boot success.*", 1))
                .withStartupTimeout(Duration.ofSeconds(300));
    }

    public void start() {
        namesrv.start();
        broker.start();
        log.info("independent rocketmq topology ready: namesrv=localhost:{}, broker=localhost:{}",
                namesrvPort, brokerPort);
    }

    public void stop() {
        try {
            broker.stop();
        } catch (Exception ignored) {
            // 清理失败不阻塞
        }
        try {
            namesrv.stop();
        } catch (Exception ignored) {
            // 清理失败不阻塞
        }
        network.close();
    }

    public String namesrvAddr() {
        return "localhost:" + namesrvPort;
    }

    public GenericContainer<?> brokerContainer() {
        return broker;
    }
}
