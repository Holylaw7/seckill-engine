package com.seckill.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.common.result.PageResult;
import com.seckill.order.config.OrderProperties;
import com.seckill.order.constant.OrderConstants;
import com.seckill.order.dto.CancelOrderMessage;
import com.seckill.order.dto.CreateOrderMessage;
import com.seckill.order.dto.OrderDetailResponse;
import com.seckill.order.dto.OrderStatusResponse;
import com.seckill.order.entity.Idempotent;
import com.seckill.order.entity.OrderItem;
import com.seckill.order.entity.SeckillOrder;
import com.seckill.order.mapper.IdempotentMapper;
import com.seckill.order.mapper.OrderItemMapper;
import com.seckill.order.mapper.SeckillOrderMapper;
import com.seckill.order.mq.CancelOrderProducer;
import com.seckill.order.state.OrderStateMachine;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final SeckillOrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final IdempotentMapper idempotentMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final OrderStateMachine orderStateMachine;
    private final CancelOrderProducer cancelOrderProducer;
    private final OrderProperties properties;

    /**
     * 建单（幂等表 + 订单 + 明细同事务；状态 CREATE → WAIT_PAY）。
     *
     * @return 订单；重复消息返回 null
     */
    @Transactional
    public SeckillOrder createOrder(CreateOrderMessage message) {
        if (message.getAmount() == null || message.getAmount() <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "消息缺少金额快照");
        }
        try {
            Idempotent idempotent = new Idempotent();
            idempotent.setId(snowflakeIdGenerator.nextId());
            idempotent.setBizType(OrderConstants.BIZ_TYPE_ORDER_CREATE);
            idempotent.setBizId(message.getMessageId());
            idempotent.setUserId(message.getUserId());
            idempotent.setStatus(1);
            idempotentMapper.insert(idempotent);
        } catch (DuplicateKeyException e) {
            return null;
        }

        long orderId = Long.parseLong(message.getOrderId());
        BigDecimal amount = BigDecimal.valueOf(message.getAmount()).movePointLeft(2);
        SeckillOrder order = new SeckillOrder();
        order.setId(orderId);
        order.setOrderNo(message.getOrderId());
        order.setUserId(message.getUserId());
        order.setSessionId(message.getSessionId());
        order.setSkuId(message.getSkuId());
        order.setQuantity(message.getQuantity());
        order.setOrderAmount(amount);
        order.setOrderStatus(OrderConstants.STATUS_CREATE);
        order.setActiveKey(message.getUserId() + OrderConstants.ACTIVE_KEY_SEPARATOR
                + message.getSessionId() + OrderConstants.ACTIVE_KEY_SEPARATOR
                + message.getSkuId());
        order.setPayDeadline(LocalDateTime.now().plusMinutes(properties.getPayDeadlineMinutes()));
        order.setVersion(0);
        orderMapper.insert(order);

        OrderItem item = new OrderItem();
        item.setId(snowflakeIdGenerator.nextId());
        item.setOrderId(orderId);
        item.setUserId(message.getUserId());
        item.setSkuId(message.getSkuId());
        item.setSkuName("秒杀商品-" + message.getSkuId());
        int quantity = message.getQuantity() == null ? 1 : message.getQuantity();
        item.setQuantity(quantity);
        item.setPrice(amount.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP));
        item.setAmount(amount);
        orderItemMapper.insert(item);

        boolean ok = orderStateMachine.transition(order, OrderConstants.STATUS_WAIT_PAY, null);
        if (!ok) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "订单状态流转失败");
        }
        return order;
    }

    /**
     * 用户取消（仅 WAIT_PAY；事务内流转 + 释放 active_key）。
     */
    @Transactional
    public SeckillOrder prepareCancel(Long userId, String orderId) {
        SeckillOrder order = getOwnedOrder(userId, orderId);
        boolean ok = orderStateMachine.transition(order, OrderConstants.STATUS_CANCEL, "用户取消");
        if (!ok) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "仅 WAIT_PAY 状态可取消");
        }
        return order;
    }

    /**
     * 超时关单（事务内流转 + 释放 active_key；冲突返回 false，由支付胜出）。
     */
    @Transactional
    public boolean closeOrder(SeckillOrder order) {
        return orderStateMachine.transition(order, OrderConstants.STATUS_TIMEOUT, "支付超时关闭");
    }

    /**
     * 发布 CANCEL_ORDER（事务外）；成功后置 SENT，失败保持 PENDING 由补偿任务补发。
     */
    public void publishCancelNotify(SeckillOrder order) {
        String reason = OrderConstants.STATUS_TIMEOUT.equals(order.getOrderStatus())
                ? OrderConstants.REASON_TIMEOUT : OrderConstants.REASON_CANCEL;
        CancelOrderMessage message = new CancelOrderMessage(
                UUID.randomUUID().toString().replace("-", ""),
                order.getOrderNo(), order.getUserId(), order.getSkuId(),
                order.getSessionId(), order.getQuantity(), reason, System.currentTimeMillis());
        if (cancelOrderProducer.send(message)) {
            markCancelNotifySent(order.getId());
        }
    }

    public void markCancelNotifySent(Long orderId) {
        orderMapper.update(null, new LambdaUpdateWrapper<SeckillOrder>()
                .eq(SeckillOrder::getId, orderId)
                .eq(SeckillOrder::getCancelNotifyStatus, OrderConstants.NOTIFY_PENDING)
                .set(SeckillOrder::getCancelNotifyStatus, OrderConstants.NOTIFY_SENT));
    }

    public List<SeckillOrder> findExpiredWaitPay(int batchSize) {
        return orderMapper.selectList(new LambdaQueryWrapper<SeckillOrder>()
                .eq(SeckillOrder::getOrderStatus, OrderConstants.STATUS_WAIT_PAY)
                .lt(SeckillOrder::getPayDeadline, LocalDateTime.now())
                .last("LIMIT " + batchSize));
    }

    public List<SeckillOrder> findPendingCancelNotify(int batchSize) {
        return orderMapper.selectList(new LambdaQueryWrapper<SeckillOrder>()
                .in(SeckillOrder::getOrderStatus, OrderConstants.STATUS_CANCEL, OrderConstants.STATUS_TIMEOUT)
                .eq(SeckillOrder::getCancelNotifyStatus, OrderConstants.NOTIFY_PENDING)
                .last("LIMIT " + batchSize));
    }

    public OrderDetailResponse getOrderDetail(Long userId, String orderId) {
        SeckillOrder order = getOwnedOrder(userId, orderId);
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()));
        return OrderDetailResponse.of(order, items);
    }

    public OrderStatusResponse getOrderStatus(Long userId, String orderId) {
        SeckillOrder order = getOwnedOrder(userId, orderId);
        return new OrderStatusResponse(order.getOrderStatus(), order.getPayDeadline());
    }

    public PageResult<SeckillOrder> listOrders(Long userId, String status, int pageNum, int pageSize) {
        LambdaQueryWrapper<SeckillOrder> wrapper = new LambdaQueryWrapper<SeckillOrder>()
                .eq(SeckillOrder::getUserId, userId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(SeckillOrder::getOrderStatus, status);
        }
        Page<SeckillOrder> page = orderMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(), pageNum, pageSize);
    }

    private SeckillOrder getOwnedOrder(Long userId, String orderId) {
        SeckillOrder order = orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>()
                .eq(SeckillOrder::getOrderNo, orderId));
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该订单");
        }
        return order;
    }
}
