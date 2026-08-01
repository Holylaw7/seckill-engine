package com.seckill.common.exception;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.result.Result;
import com.seckill.common.trace.TraceIdUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：统一转换为 {@link Result}，禁止向客户端返回堆栈。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("business exception, traceId={}, code={}, message={}",
                TraceIdUtils.get(), e.getErrorCode().getCode(), e.getMessage());
        return Result.error(e.getErrorCode());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getDefaultMessage())
                .orElse(ErrorCode.PARAM_ERROR.getMessage());
        log.warn("param invalid, traceId={}, message={}", TraceIdUtils.get(), message);
        return Result.error(ErrorCode.PARAM_ERROR.getCode(), message);
    }

    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getDefaultMessage())
                .orElse(ErrorCode.PARAM_ERROR.getMessage());
        log.warn("bind invalid, traceId={}, message={}", TraceIdUtils.get(), message);
        return Result.error(ErrorCode.PARAM_ERROR.getCode(), message);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("unexpected exception, traceId={}", TraceIdUtils.get(), e);
        return Result.error(ErrorCode.SYSTEM_ERROR);
    }
}
