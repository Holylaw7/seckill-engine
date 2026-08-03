package com.seckill.integration;

import com.seckill.common.result.Result;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.RocketMqTestConsumer;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.payment.PaymentApplication;
import com.seckill.payment.dto.CreatePayResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SC-05 支付回调链路：首次成功 / 重复回调 / 金额异常 / 验签失败。
 * 已知边界：order-service 未实现 PAY_SUCCESS 消费端，本阶段断言止于消息发布。
 */
@Tag("integration")
class PaymentCallbackFlowIT extends IntegrationTestBase {

    private static final long USER_ID = 15001L;
    private static final String ORDER_PREFIX = "test-pay-order-";
    private static final String AMOUNT = "99.00";
    private static final String MOCK_SECRET = "mock-channel-secret";

    private static ServiceLauncher.RunningService PAYMENT;

    @BeforeAll
    static void startServices() throws Exception {
        // 预热消息：触发 topic 路由在 namesrv 生效，避免 PAY_SUCCESS 发送时 No route info
        sendRocketMqMessage("seckill-order-tx", "TEST_WARMUP", "{\"warmup\":true}");
        PAYMENT = ServiceSupport.start(PaymentApplication.class, "payment", "seckill_payment", "payment-service",
                "--seckill.payment.refund-compensate.period-seconds=3600");
    }

    @AfterAll
    static void stopServices() {
        if (PAYMENT != null) {
            PAYMENT.stop();
        }
    }

    @BeforeEach
    void reset() throws Exception {
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
        TestDataHelper.cleanupPaymentNamespace(ORDER_PREFIX);
    }

    @Test
    void firstCallback_should_mark_pay_success_and_publish_once() throws Exception {
        try (RocketMqTestConsumer consumer = RocketMqTestConsumer.start(
                rocketMqNameServer(), "seckill-order-tx", "PAY_SUCCESS")) {
            String paymentNo = createPayment("test-pay-first");
            ResponseEntity<String> response = callback(paymentNo, "TXN-FIRST-1", AMOUNT, "test-pay-first-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("success");

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(queryString("SELECT status FROM seckill_payment.payment_order WHERE payment_no='" + paymentNo + "'"))
                        .isEqualTo("PAY_SUCCESS");
                assertThat(queryInt("SELECT version FROM seckill_payment.payment_order WHERE payment_no='" + paymentNo + "'"))
                        .isEqualTo(2);
                assertThat(queryString("SELECT transaction_no FROM seckill_payment.payment_order WHERE payment_no='" + paymentNo + "'"))
                        .isEqualTo("TXN-FIRST-1");
                assertThat(queryString("SELECT pay_time FROM seckill_payment.payment_order WHERE payment_no='" + paymentNo + "'"))
                        .isNotNull();
                assertThat(queryInt("SELECT COUNT(*) FROM seckill_payment.payment_callback_log "
                        + "WHERE channel_transaction_no='TXN-FIRST-1'")).isEqualTo(1);
                assertThat(queryString("SELECT verify_result FROM seckill_payment.payment_callback_log "
                        + "WHERE channel_transaction_no='TXN-FIRST-1'")).isEqualTo("VERIFY_OK");
                assertThat(queryString("SELECT process_status FROM seckill_payment.payment_callback_log "
                        + "WHERE channel_transaction_no='TXN-FIRST-1'")).isEqualTo("SUCCESS");
            });
            consumer.awaitCount(paymentNo, 1, Duration.ofSeconds(30));
        }
    }

    @Test
    void duplicateCallback_should_be_idempotent() throws Exception {
        try (RocketMqTestConsumer consumer = RocketMqTestConsumer.start(
                rocketMqNameServer(), "seckill-order-tx", "PAY_SUCCESS")) {
            String paymentNo = createPayment("test-pay-dup");
            ResponseEntity<String> first = callback(paymentNo, "TXN-DUP-1", AMOUNT, "test-pay-dup-1");
            ResponseEntity<String> second = callback(paymentNo, "TXN-DUP-1", AMOUNT, "test-pay-dup-2");

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(queryString("SELECT status FROM seckill_payment.payment_order WHERE payment_no='" + paymentNo + "'"))
                        .isEqualTo("PAY_SUCCESS");
                assertThat(queryInt("SELECT version FROM seckill_payment.payment_order WHERE payment_no='" + paymentNo + "'"))
                        .isEqualTo(2);
                assertThat(queryInt("SELECT COUNT(*) FROM seckill_payment.payment_callback_log "
                        + "WHERE channel_transaction_no='TXN-DUP-1'")).isEqualTo(1);
            });
            consumer.awaitCount(paymentNo, 1, Duration.ofSeconds(30));
        }
    }

    @Test
    void amountMismatch_should_reject_and_keep_wait_pay() throws Exception {
        try (RocketMqTestConsumer consumer = RocketMqTestConsumer.start(
                rocketMqNameServer(), "seckill-order-tx", "PAY_SUCCESS")) {
            String paymentNo = createPayment("test-pay-amount");
            ResponseEntity<String> response = callback(paymentNo, "TXN-AMT-1", "88.00", "test-pay-amount-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(queryString("SELECT status FROM seckill_payment.payment_order WHERE payment_no='" + paymentNo + "'"))
                    .isEqualTo("WAIT_PAY");
            assertThat(queryInt("SELECT version FROM seckill_payment.payment_order WHERE payment_no='" + paymentNo + "'"))
                    .isEqualTo(1);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(queryString("SELECT verify_result FROM seckill_payment.payment_callback_log "
                            + "WHERE channel_transaction_no='TXN-AMT-1'")).isEqualTo("AMOUNT_MISMATCH"));
            consumer.awaitNoMessage(paymentNo, Duration.ofSeconds(3));
        }
    }

    @Test
    void invalidSignature_should_reject_and_keep_wait_pay() throws Exception {
        try (RocketMqTestConsumer consumer = RocketMqTestConsumer.start(
                rocketMqNameServer(), "seckill-order-tx", "PAY_SUCCESS")) {
            String paymentNo = createPayment("test-pay-sign");
            long timestamp = System.currentTimeMillis() / 1000;
            ResponseEntity<String> response = TestHttp.callback(
                    "http://localhost:" + PAYMENT.port(), paymentNo, "TXN-SIGN-1", AMOUNT,
                    timestamp, "invalid-signature", "test-pay-sign-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(queryString("SELECT status FROM seckill_payment.payment_order WHERE payment_no='" + paymentNo + "'"))
                    .isEqualTo("WAIT_PAY");
            assertThat(queryInt("SELECT version FROM seckill_payment.payment_order WHERE payment_no='" + paymentNo + "'"))
                    .isEqualTo(1);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(queryInt("SELECT COUNT(*) FROM seckill_payment.payment_callback_log "
                        + "WHERE channel_transaction_no='TXN-SIGN-1'")).isEqualTo(1);
                assertThat(queryString("SELECT verify_result FROM seckill_payment.payment_callback_log "
                        + "WHERE channel_transaction_no='TXN-SIGN-1'")).isEqualTo("VERIFY_FAIL");
                assertThat(queryString("SELECT process_status FROM seckill_payment.payment_callback_log "
                        + "WHERE channel_transaction_no='TXN-SIGN-1'")).isEqualTo("FAILED");
            });
            consumer.awaitNoMessage(paymentNo, Duration.ofSeconds(3));
        }
    }

    private static String createPayment(String traceId) {
        String orderNo = ORDER_PREFIX + UUID.randomUUID();
        Result<CreatePayResponse> result = TestHttp.createPayment(
                "http://localhost:" + PAYMENT.port(), orderNo, USER_ID, AMOUNT);
        assertThat(result).isNotNull();
        assertThat(result.getCode()).isZero();
        return result.getData().getPaymentNo();
    }

    private static ResponseEntity<String> callback(String paymentNo, String transactionNo,
                                                   String amount, String traceId) {
        long timestamp = System.currentTimeMillis() / 1000;
        String sign = TestHttp.signCallback(paymentNo, transactionNo, amount, timestamp, MOCK_SECRET);
        return TestHttp.callback("http://localhost:" + PAYMENT.port(), paymentNo, transactionNo,
                amount, timestamp, sign, traceId);
    }
}
