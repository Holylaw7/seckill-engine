package com.seckill.common.exception;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.result.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionShouldMapToErrorCode() {
        Result<Void> result = handler.handleBusinessException(new BusinessException(ErrorCode.STOCK_EMPTY));
        assertEquals(30004, result.getCode());
        assertEquals("库存不足", result.getMessage());
    }

    @Test
    void unknownExceptionShouldMapToSystemErrorWithoutStack() {
        Result<Void> result = handler.handleException(new IllegalStateException("secret stack"));
        assertEquals(10000, result.getCode());
        assertFalse(result.getMessage().contains("secret stack"));
    }
}
