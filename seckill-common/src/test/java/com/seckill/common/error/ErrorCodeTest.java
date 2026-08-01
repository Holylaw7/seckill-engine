package com.seckill.common.error;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
