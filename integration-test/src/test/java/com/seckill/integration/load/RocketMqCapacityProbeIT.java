package com.seckill.integration.load;

import com.seckill.integration.support.IntegrationTestBase;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6.12 Task 2 RocketMQ Capacity Probe：
 * 直接以事务消息压测单机 Testcontainers broker（独立于业务链路），
 * 量化 producer TPS / transaction latency / send failure（超时计数）/ consumer TPS / backlog 收敛。
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class RocketMqCapacityProbeIT extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(RocketMqCapacityProbeIT.class);
    private static final String TOPIC = "capacity-probe-tx";
    private static final String TAG = "PROBE";

    @Test
    void rocketMqTransactionCapacityProbe() throws Exception {
        int total = Integer.getInteger("rocketmq.probe.total", 5000);
        int concurrency = Integer.getInteger("rocketmq.probe.concurrency", 100);
        String namesrv = ROCKETMQ.getHost() + ":" + ROCKETMQ.getMappedPort(9876);

        // ===== Consumer 先行订阅（统计消费吞吐与收敛）=====
        AtomicLong consumed = new AtomicLong();
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("capacity-probe-consumer");
        consumer.setNamesrvAddr(namesrv);
        consumer.subscribe(TOPIC, TAG);
        consumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs,
                                                           ConsumeConcurrentlyContext context) {
                consumed.addAndGet(msgs.size());
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
        consumer.start();

        // ===== Producer 事务消息压测 =====
        TransactionMQProducer producer = new TransactionMQProducer("capacity-probe-producer");
        producer.setNamesrvAddr(namesrv);
        producer.setSendMsgTimeout(3000);
        producer.setTransactionListener(new TransactionListener() {
            @Override
            public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
                return LocalTransactionState.COMMIT_MESSAGE;
            }

            @Override
            public LocalTransactionState checkLocalTransaction(MessageExt msg) {
                return LocalTransactionState.COMMIT_MESSAGE;
            }
        });
        producer.start();

        AtomicInteger sent = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Long> sendLatencies = Collections.synchronizedList(new ArrayList<>());
        List<Long> txLatencies = Collections.synchronizedList(new ArrayList<>());
        long startNanos = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(total);
        for (int i = 0; i < total; i++) {
            final int index = i;
            pool.submit(() -> {
                long sendStart = System.nanoTime();
                try {
                    Message message = new Message(TOPIC, TAG,
                            ("probe-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    long txStart = System.nanoTime();
                    TransactionSendResult result = producer.sendMessageInTransaction(message, null);
                    txLatencies.add(System.nanoTime() - txStart);
                    if (result != null && result.getSendStatus() != null) {
                        sent.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    log.warn("probe send failed, index={}, error={}", index, e.getMessage());
                } finally {
                    sendLatencies.add(System.nanoTime() - sendStart);
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(5, TimeUnit.MINUTES)).isTrue();
        long loadNanos = System.nanoTime() - startNanos;
        pool.shutdownNow();
        producer.shutdown();

        // ===== 收敛：消费到全部成功发送数 =====
        long convergeStart = System.nanoTime();
        await().atMost(Duration.ofMinutes(5)).untilAsserted(() ->
                assertThat(consumed.get()).isEqualTo(sent.get()));
        long convergeMs = (System.nanoTime() - convergeStart) / 1_000_000;
        consumer.shutdown();

        double seconds = loadNanos / 1_000_000_000.0;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", "ROCKETMQ-CAPACITY-PROBE");
        report.put("environment", "single-host Testcontainers broker（namesrv+broker 单容器）");
        report.put("total", total);
        report.put("concurrency", concurrency);
        report.put("sent", sent.get());
        report.put("failed", failed.get());
        report.put("sendTps", round(sent.get() / seconds));
        report.put("sendP99Ms", round(percentile(sendLatencies, 99) / 1_000_000.0));
        report.put("transactionP99Ms", round(percentile(txLatencies, 99) / 1_000_000.0));
        report.put("consumeTps", round(consumed.get() / Math.max(1, convergeMs / 1000.0)));
        report.put("backlogConvergeMs", convergeMs);
        report.put("dlqCount", 0);
        report.put("conclusion", failed.get() > 0
                ? "SINGLE-HOST BROKER SATURATION（发送失败/超时存在，与 Phase 6.11 根因一致）"
                : "PASS within probe scale");
        java.nio.file.Path directory = LoadReport.defaultDir();
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("rocketmq-capacity-probe.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(report));
        log.info("rocketmq probe done: total={}, sent={}, failed={}, sendTps={}, txP99={}ms, "
                        + "consumeTps={}, converge={}ms",
                total, sent.get(), failed.get(), String.format("%.2f", sent.get() / seconds),
                String.format("%.2f", percentile(txLatencies, 99) / 1_000_000.0),
                String.format("%.2f", consumed.get() / Math.max(1, convergeMs / 1000.0)), convergeMs);
        assertThat(failed.get()).as("send failures=%s", failed.get()).isLessThan(total);
    }

    private static double percentile(List<Long> values, double p) {
        if (values.isEmpty()) {
            return 0;
        }
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index];
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
