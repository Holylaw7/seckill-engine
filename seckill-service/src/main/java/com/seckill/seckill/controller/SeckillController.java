package com.seckill.seckill.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.result.PageResult;
import com.seckill.common.result.Result;
import com.seckill.seckill.constant.SeckillConstants;
import com.seckill.seckill.dto.ExecuteRequest;
import com.seckill.seckill.dto.ExecuteResponse;
import com.seckill.seckill.dto.ResultQueryResponse;
import com.seckill.seckill.dto.SessionResponse;
import com.seckill.seckill.entity.SeckillSession;
import com.seckill.seckill.mapper.SeckillSessionMapper;
import com.seckill.seckill.service.ResultQueryService;
import com.seckill.seckill.service.SeckillService;
import com.seckill.seckill.service.SessionCacheService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final ResultQueryService resultQueryService;
    private final SessionCacheService sessionCacheService;
    private final SeckillSessionMapper sessionMapper;

    @PostMapping("/execute")
    public Result<ExecuteResponse> execute(@Valid @RequestBody ExecuteRequest request,
                                           @RequestHeader(value = SeckillConstants.HEADER_USER_ID, required = false) String userId,
                                           HttpServletRequest httpRequest) {
        requireUserId(userId);
        return Result.success(seckillService.execute(
                Long.parseLong(userId), resolveIp(httpRequest), request));
    }

    @GetMapping("/result")
    public Result<ResultQueryResponse> result(@RequestParam Long sessionId,
                                              @RequestParam Long skuId,
                                              @RequestHeader(value = SeckillConstants.HEADER_USER_ID, required = false) String userId) {
        requireUserId(userId);
        return Result.success(resultQueryService.query(Long.parseLong(userId), sessionId, skuId));
    }

    @GetMapping("/session/{sessionId}")
    public Result<SessionResponse> session(@PathVariable Long sessionId) {
        SessionCacheService.SessionCache cache = sessionCacheService.getSession(sessionId);
        if (cache == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "场次不存在");
        }
        return Result.success(new SessionResponse(
                cache.id(), cache.activityId(), cache.name(),
                cache.startTime(), cache.endTime(), cache.status(), cache.limitPerUser()));
    }

    @GetMapping("/sessions")
    public Result<PageResult<SeckillSession>> sessions(@RequestParam(required = false) Long activityId,
                                                       @RequestParam(defaultValue = "1") int pageNum,
                                                       @RequestParam(defaultValue = "10") int pageSize) {
        LambdaQueryWrapper<SeckillSession> wrapper = activityId == null
                ? null
                : new LambdaQueryWrapper<SeckillSession>().eq(SeckillSession::getActivityId, activityId);
        Page<SeckillSession> page = sessionMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return Result.success(PageResult.of(page.getRecords(), page.getTotal(), pageNum, pageSize));
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private static String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
