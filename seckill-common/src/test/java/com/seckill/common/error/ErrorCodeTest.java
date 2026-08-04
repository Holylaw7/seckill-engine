package com.seckill.common.error;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.Tag("unit")
class ErrorCodeTest {

    @Test
    void allCodesShouldBeUniqueAndNonEmpty() {
        Set<Integer> codes = new HashSet<>();
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertTrue(codes.add(errorCode.getCode()), "duplicate code: " + errorCode.getCode());
            assertFalse(errorCode.getMessage().isBlank(), "empty message: " + errorCode.name());
        }
    }

    @Test
    void successShouldBeZero() {
        assertEquals(0, ErrorCode.SUCCESS.getCode());
    }

    @Test
    void classificationShouldBeFrozen() {
        assertTrue(ErrorCode.PARAM_ERROR.getCode() >= 10000 && ErrorCode.PARAM_ERROR.getCode() < 20000);
        assertTrue(ErrorCode.UNAUTHORIZED.getCode() >= 20000 && ErrorCode.UNAUTHORIZED.getCode() < 30000);
        assertTrue(ErrorCode.STOCK_EMPTY.getCode() >= 30000 && ErrorCode.STOCK_EMPTY.getCode() < 40000);
        assertTrue(ErrorCode.ORDER_NOT_FOUND.getCode() >= 40000 && ErrorCode.ORDER_NOT_FOUND.getCode() < 50000);
        assertTrue(ErrorCode.PAYMENT_NOT_FOUND.getCode() >= 50000 && ErrorCode.PAYMENT_NOT_FOUND.getCode() < 60000);
    }

    @Test
    void should_keep_all_codes_in_frozen_segments_when_enumerated() {
        // Arrange
        long[] segments = new long[5];

        // Act
        for (ErrorCode errorCode : ErrorCode.values()) {
            if (errorCode == ErrorCode.SUCCESS) {
                continue;
            }
            int code = errorCode.getCode();
            assertThat(code)
                    .as("code out of frozen segment: %s=%d", errorCode.name(), code)
                    .isBetween(10000, 59999);
            segments[(code / 10000) - 1]++;
        }

        // Assert
        for (int i = 0; i < segments.length; i++) {
            assertThat(segments[i])
                    .as("frozen segment %d has no error code", i + 1)
                    .isPositive();
        }
    }
}
