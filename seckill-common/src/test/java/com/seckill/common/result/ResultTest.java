package com.seckill.common.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.trace.TraceIdUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void successShouldBuildDefaultResult() {
        Result<String> result = Result.success();
        assertEquals(0, result.getCode());
        assertEquals("success", result.getMessage());
        assertNull(result.getData());
        assertNotNull(result.getTimestamp());
    }

    @Test
    void successWithDataShouldContainData() {
        Result<String> result = Result.success("hello");
        assertEquals("hello", result.getData());
    }

    @Test
    void errorShouldMapErrorCode() {
        Result<Void> result = Result.error(ErrorCode.STOCK_EMPTY);
        assertEquals(30004, result.getCode());
        assertEquals("库存不足", result.getMessage());
    }

    @Test
    void errorWithCustomMessageShouldOverwrite() {
        Result<Void> result = Result.error(ErrorCode.PARAM_ERROR.getCode(), "quantity 必须大于 0");
        assertEquals(10001, result.getCode());
        assertEquals("quantity 必须大于 0", result.getMessage());
    }

    @Test
    void serializationShouldContainAllFields() throws Exception {
        TraceIdUtils.set("trace-123");
        try {
            Result<String> result = Result.success("data");
            String json = objectMapper.writeValueAsString(result);
            assertEquals(0, objectMapper.readTree(json).get("code").asInt());
            assertEquals("data", objectMapper.readTree(json).get("data").asText());
            assertEquals("trace-123", objectMapper.readTree(json).get("traceId").asText());
            assertEquals(5, objectMapper.readTree(json).size());
        } finally {
            TraceIdUtils.clear();
        }
    }
}
