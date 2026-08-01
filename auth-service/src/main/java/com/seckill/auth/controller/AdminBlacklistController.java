package com.seckill.auth.controller;

import com.seckill.auth.constant.AuthConstants;
import com.seckill.auth.dto.BlacklistRequest;
import com.seckill.auth.entity.RiskBlacklist;
import com.seckill.auth.service.BlacklistService;
import com.seckill.common.result.PageResult;
import com.seckill.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端黑名单接口（JWT + ADMIN 角色由 AdminAuthInterceptor 校验）。
 */
@RestController
@RequestMapping("/api/v1/auth/admin/blacklist")
@RequiredArgsConstructor
public class AdminBlacklistController {

    private final BlacklistService blacklistService;

    @PostMapping
    public Result<Void> add(@Valid @RequestBody BlacklistRequest request,
                            @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) String operatorId) {
        blacklistService.add(request, operatorId == null ? null : Long.parseLong(operatorId));
        return Result.success();
    }

    @DeleteMapping
    public Result<Void> remove(@RequestParam String bizType, @RequestParam String bizValue) {
        blacklistService.remove(bizType, bizValue);
        return Result.success();
    }

    @GetMapping
    public Result<PageResult<RiskBlacklist>> list(@RequestParam(defaultValue = "1") int pageNum,
                                                  @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(blacklistService.list(pageNum, pageSize));
    }
}
