package com.seckill.common.result;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.trace.TraceIdUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void should_round_trip_nested_generic_result_when_using_type_reference() throws Exception {
        // Arrange
        PageResult<String> page = PageResult.of(List.of("a", "b"), 100L, 1, 10);
        Result<PageResult<String>> result = Result.success(page);

        // Act
        String json = objectMapper.writeValueAsString(result);
        Result<PageResult<String>> restored = objectMapper.readValue(
                json, new TypeReference<Result<PageResult<String>>>() {
                });

        // Assert
        assertThat(restored.getCode()).isZero();
        assertThat(restored.getMessage()).isEqualTo("success");
        assertThat(restored.getData()).isNotNull();
        assertThat(restored.getData().getList()).containsExactly("a", "b");
        assertThat(restored.getData().getTotal()).isEqualTo(100L);
        assertThat(restored.getData().getPageNum()).isEqualTo(1);
        assertThat(restored.getData().getPageSize()).isEqualTo(10);
    }
}
