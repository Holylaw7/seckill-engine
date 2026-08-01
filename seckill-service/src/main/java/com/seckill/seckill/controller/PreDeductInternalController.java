package com.seckill.seckill.controller;

import com.seckill.common.result.Result;
import com.seckill.seckill.dto.PreDeductConfirmRequest;
import com.seckill.seckill.service.PreDeductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口：order-service 建单成功后回填确认（禁止跨库，经接口）。
 */
@RestController
@RequestMapping("/api/v1/seckill/internal")
@RequiredArgsConstructor
public class PreDeductInternalController {

    private final PreDeductService preDeductService;

    @PostMapping("/pre-deducts/confirm")
    public Result<Void> confirm(@Valid @RequestBody PreDeductConfirmRequest request) {
        preDeductService.confirm(request.getMessageId(), request.getOrderId());
        return Result.success();
    }
}
