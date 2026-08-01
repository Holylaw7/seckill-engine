package com.seckill.gateway.handler;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.result.Result;
import com.seckill.gateway.constant.GatewayConstants;
import com.seckill.gateway.util.GatewayResponses;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关统一异常处理：输出统一 JSON，禁止向客户端返回堆栈。
 */
@Slf4j
@Component
@Order(-2)
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        Result<Void> result = Result.error(ErrorCode.SYSTEM_ERROR);

        if (ex instanceof ResponseStatusException responseStatusException) {
            status = HttpStatus.valueOf(responseStatusException.getStatusCode().value());
            if (HttpStatus.NOT_FOUND.equals(status)) {
                result = Result.error(ErrorCode.RESOURCE_NOT_FOUND);
            }
        }

        String traceId = exchange.getRequest().getHeaders().getFirst(GatewayConstants.TRACE_ID_HEADER);
        log.error("gateway error, traceId={}, status={}", traceId, status.value(), ex);
        return GatewayResponses.writeJson(exchange, status, result);
    }
}
