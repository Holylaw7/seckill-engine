package com.seckill.inventory.controller;

import com.seckill.common.result.Result;
import com.seckill.inventory.constant.InventoryConstants;
import com.seckill.inventory.dto.ReconcileReport;
import com.seckill.inventory.dto.RepairRequest;
import com.seckill.inventory.service.ReconciliationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对账修复管理接口（管理员鉴权在网关/统一管理端接入，Phase 7 完善）。
 */
@RestController
@RequestMapping("/api/v1/inventory/admin")
@RequiredArgsConstructor
public class InventoryAdminController {

    private final ReconciliationService reconciliationService;

    @GetMapping("/reconcile")
    public Result<ReconcileReport> reconcile(@RequestParam Long skuId) {
        return Result.success(reconciliationService.check(skuId));
    }

    @PostMapping("/reconcile/repair")
    public Result<String> repair(@Valid @RequestBody RepairRequest request,
                                 @RequestHeader(value = InventoryConstants.HEADER_USER_ID, required = false) String operatorId) {
        String flowNo = reconciliationService.repair(
                request.getSkuId(), request.getTargetAvailable(), request.getReason(),
                operatorId == null ? null : Long.parseLong(operatorId));
        return Result.success(flowNo);
    }
}
