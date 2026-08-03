package com.seckill.integration.support;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 测试侧 MQ 校验消费者：唯一消费组订阅指定 tag，按业务 key 过滤消息。
 */
public final class RocketMqTestConsumer implements AutoCloseable {

    private final DefaultMQPushConsumer consumer;
    private final BlockingQueue<MessageExt> messages;

    private RocketMqTestConsumer(DefaultMQPushConsumer consumer, BlockingQueue<MessageExt> messages) {
        this.consumer = consumer;
        this.messages = messages;
    }

    public static RocketMqTestConsumer start(String nameServer, String topic, String tag) throws Exception {
        BlockingQueue<MessageExt> queue = new LinkedBlockingQueue<>();
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(
                "integration-verifier-" + UUID.randomUUID().toString().replace("-", ""));
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(topic, tag);
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            queue.addAll(msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        return new RocketMqTestConsumer(consumer, queue);
    }

    public long count(String paymentNo) {
        return messages.stream()
                .filter(message -> new String(message.getBody(), StandardCharsets.UTF_8).contains(paymentNo))
                .count();
    }

    public void awaitCount(String paymentNo, long expected, Duration timeout) {
        await().atMost(timeout).until(() -> count(paymentNo) == expected);
    }

    public void awaitNoMessage(String paymentNo, Duration quietPeriod) {
        await().atMost(quietPeriod.plus(Duration.ofSeconds(10))).pollDelay(quietPeriod)
                .untilAsserted(() -> assertThat(count(paymentNo)).isZero());
    }

    @Override
    public void close() {
        consumer.shutdown();
    }
}
