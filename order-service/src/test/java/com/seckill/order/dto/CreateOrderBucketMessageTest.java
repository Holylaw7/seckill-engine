package com.seckill.order.dto;

import com.seckill.common.util.JsonUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CREATE_ORDER 消息 bucketNo 可选字段兼容性（Phase 6.2）。
 */
@Tag("unit")
class CreateOrderBucketMessageTest {

    @Test
    void shouldParseBucketNoWhenPresent() {
        String json = "{\"messageId\":\"m1\",\"userId\":10001,\"skuId\":20001,"
                + "\"sessionId\":30001,\"orderId\":\"123\",\"timestamp\":1000,"
                + "\"quantity\":1,\"amount\":9900,\"bucketNo\":3}";
        CreateOrderMessage message = JsonUtils.fromJson(json, CreateOrderMessage.class);
        assertEquals(Integer.valueOf(3), message.getBucketNo());
        assertEquals("m1", message.getMessageId());
    }

    @Test
    void shouldParseLegacyMessageWithoutBucketNo() {
        String json = "{\"messageId\":\"m1\",\"userId\":10001,\"skuId\":20001,"
                + "\"sessionId\":30001,\"orderId\":\"123\",\"timestamp\":1000,"
                + "\"quantity\":1,\"amount\":9900}";
        CreateOrderMessage message = JsonUtils.fromJson(json, CreateOrderMessage.class);
        assertNull(message.getBucketNo());
    }
}
