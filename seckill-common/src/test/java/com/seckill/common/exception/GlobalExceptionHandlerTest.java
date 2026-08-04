package com.seckill.common.exception;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.result.Result;
import com.seckill.common.trace.TraceIdUtils;
import org.springframework.core.MethodParameter;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@org.junit.jupiter.api.Tag("unit")
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

    @Test
    void should_return_validation_error_when_argument_invalid() throws Exception {
        // Arrange
        TraceIdUtils.set("trace-valid");
        Method method = Object.class.getMethod("toString");
        MethodParameter parameter = new MethodParameter(method, -1);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "username", "username 不能为空"));
        bindingResult.addError(new FieldError("target", "password", "password 不能为空"));
        try {
            // Act
            Result<Void> result = handler.handleMethodArgumentNotValid(
                    new MethodArgumentNotValidException(parameter, bindingResult));

            // Assert
            assertThat(result.getCode()).isEqualTo(10001);
            assertThat(result.getMessage()).isEqualTo("username 不能为空");
            assertThat(result.getTraceId()).isEqualTo("trace-valid");
            assertThat(result.getTimestamp()).isNotNull();
            assertThat(result.getData()).isNull();
            assertThat(result.getMessage()).doesNotContain("Exception");
        } finally {
            TraceIdUtils.clear();
        }
    }

    @Test
    void should_return_unified_result_when_bind_exception() {
        // Arrange
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "quantity", "quantity 必须大于 0"));

        // Act
        Result<Void> result = handler.handleBindException(new BindException(bindingResult));

        // Assert
        assertThat(result.getCode()).isEqualTo(10001);
        assertThat(result.getMessage()).isEqualTo("quantity 必须大于 0");
        assertThat(result.getTraceId()).isNull();
        assertThat(result.getTimestamp()).isNotNull();
        assertThat(result.getData()).isNull();
        assertThat(result.getMessage()).doesNotContain("BindException");
    }
}
