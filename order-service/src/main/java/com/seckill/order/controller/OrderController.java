package com.seckill.order.controller;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.result.PageResult;
import com.seckill.common.result.Result;
import com.seckill.order.constant.OrderConstants;
import com.seckill.order.dto.OrderDetailResponse;
import com.seckill.order.dto.OrderStatusResponse;
import com.seckill.order.entity.SeckillOrder;
import com.seckill.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/{orderId}")
    public Result<OrderDetailResponse> detail(@PathVariable("orderId") String orderId,
                                              @RequestHeader(value = OrderConstants.HEADER_USER_ID, required = false) String userId) {
        requireUserId(userId);
        return Result.success(orderService.getOrderDetail(Long.parseLong(userId), orderId));
    }

    @GetMapping
    public Result<PageResult<SeckillOrder>> list(@RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "1") int pageNum,
                                                 @RequestParam(defaultValue = "10") int pageSize,
                                                 @RequestHeader(value = OrderConstants.HEADER_USER_ID, required = false) String userId) {
        requireUserId(userId);
        return Result.success(orderService.listOrders(Long.parseLong(userId), status, pageNum, pageSize));
    }

    @PostMapping("/{orderId}/cancel")
    public Result<Void> cancel(@PathVariable("orderId") String orderId,
                               @RequestHeader(value = OrderConstants.HEADER_USER_ID, required = false) String userId) {
        requireUserId(userId);
        SeckillOrder order = orderService.prepareCancel(Long.parseLong(userId), orderId);
        orderService.publishCancelNotify(order);
        return Result.success();
    }

    @GetMapping("/{orderId}/status")
    public Result<OrderStatusResponse> status(@PathVariable("orderId") String orderId,
                                              @RequestHeader(value = OrderConstants.HEADER_USER_ID, required = false) String userId) {
        requireUserId(userId);
        return Result.success(orderService.getOrderStatus(Long.parseLong(userId), orderId));
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
