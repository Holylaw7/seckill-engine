package com.seckill.common.exception;

import com.seckill.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.Tag("unit")
class BusinessExceptionTest {

    @Test
    void shouldBeRuntimeExceptionWithErrorCode() {
        BusinessException e = new BusinessException(ErrorCode.REPEAT_BUY);
        assertTrue(e instanceof RuntimeException);
        assertSame(ErrorCode.REPEAT_BUY, e.getErrorCode());
        assertEquals("请勿重复抢购", e.getMessage());
    }

    @Test
    void customMessageShouldOverride() {
        BusinessException e = new BusinessException(ErrorCode.PARAM_ERROR, "sessionId 不能为空");
        assertEquals(ErrorCode.PARAM_ERROR, e.getErrorCode());
        assertEquals("sessionId 不能为空", e.getMessage());
    }

    @Test
    void causeShouldBePreserved() {
        RuntimeException cause = new RuntimeException("db down");
        BusinessException e = new BusinessException(ErrorCode.SYSTEM_ERROR, cause);
        assertSame(cause, e.getCause());
    }
}
