package com.seckill.order.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.order.config.OrderProperties;
import com.seckill.order.constant.OrderConstants;
import com.seckill.order.dto.CancelOrderMessage;
import com.seckill.order.dto.CreateOrderMessage;
import com.seckill.order.dto.PaySuccessMessage;
import com.seckill.order.entity.Idempotent;
import com.seckill.order.entity.OrderItem;
import com.seckill.order.entity.SeckillOrder;
import com.seckill.order.mapper.IdempotentMapper;
import com.seckill.order.mapper.OrderItemMapper;
import com.seckill.order.mapper.SeckillOrderMapper;
import com.seckill.order.mq.CancelOrderProducer;
import com.seckill.order.state.OrderStateMachine;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class OrderServiceImplTest {

    @Mock
    private SeckillOrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private IdempotentMapper idempotentMapper;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;
    @Mock
    private CancelOrderProducer cancelOrderProducer;

    private OrderProperties properties;
    private OrderService orderService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SeckillOrder.class);
        TableInfoHelper.initTableInfo(assistant, OrderItem.class);
        TableInfoHelper.initTableInfo(assistant, Idempotent.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        properties = new OrderProperties();
        properties.setPayDeadlineMinutes(15);
        OrderStateMachine stateMachine = new OrderStateMachine(orderMapper);
        orderService = new OrderService(orderMapper, orderItemMapper, idempotentMapper,
                snowflakeIdGenerator, stateMachine, cancelOrderProducer, properties);
    }

    private CreateOrderMessage message() {
        return new CreateOrderMessage(
                "msg-001", 10001L, 20001L, 30001L, "123", 1000L, 1, 9900L, null, null);
    }

    private SeckillOrder waitPayOrder() {
        SeckillOrder order = new SeckillOrder();
        order.setId(123L);
        order.setOrderNo("123");
        order.setUserId(10001L);
        order.setSessionId(30001L);
        order.setSkuId(20001L);
        order.setQuantity(1);
        order.setOrderAmount(new BigDecimal("99.00"));
        order.setOrderStatus("WAIT_PAY");
        order.setActiveKey("10001:30001:20001");
        order.setVersion(2);
        return order;
    }

    private PaySuccessMessage paySuccessMessage() {
        return new PaySuccessMessage("pay-msg-001", "P-001", "123",
                10001L, new BigDecimal("99.00"), "TXN-001", System.currentTimeMillis());
    }

    @Test
    void createOrderShouldCreateOrderAndItem() {
        when(orderMapper.update(isNull(), any())).thenReturn(1);

        SeckillOrder order = orderService.createOrder(message());
        assertNotNull(order);

        ArgumentCaptor<SeckillOrder> orderCaptor = ArgumentCaptor.forClass(SeckillOrder.class);
        verify(orderMapper).insert(orderCaptor.capture());
        assertEquals("CREATE", orderCaptor.getValue().getOrderStatus());
        assertEquals("10001:30001:20001", orderCaptor.getValue().getActiveKey());
        assertEquals(new BigDecimal("99.00"), orderCaptor.getValue().getOrderAmount());
        assertNotNull(orderCaptor.getValue().getPayDeadline());

        ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper).insert(itemCaptor.capture());
        assertEquals(new BigDecimal("99.00"), itemCaptor.getValue().getAmount());
        assertEquals(new BigDecimal("99.00"), itemCaptor.getValue().getPrice());
    }

    @Test
    void createOrderDuplicateShouldReturnNull() {
        doThrow(new DuplicateKeyException("dup")).when(idempotentMapper).insert(any(Idempotent.class));
        assertNull(orderService.createOrder(message()));
        verify(orderMapper, never()).insert(any(SeckillOrder.class));
    }

    @Test
    void createOrderMissingAmountShouldThrow() {
        CreateOrderMessage message = message();
        message.setAmount(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.createOrder(message));
        assertEquals(ErrorCode.PARAM_ERROR, e.getErrorCode());
    }

    @Test
    void paySuccessShouldTransitionWaitPayOrder() {
        when(orderMapper.selectOne(any())).thenReturn(waitPayOrder());
        when(orderMapper.update(isNull(), any())).thenReturn(1);

        orderService.processPaySuccess(paySuccessMessage());

        ArgumentCaptor<Idempotent> captor = ArgumentCaptor.forClass(Idempotent.class);
        verify(idempotentMapper).insert(captor.capture());
        assertEquals(OrderConstants.BIZ_TYPE_PAY_SUCCESS, captor.getValue().getBizType());
        assertEquals("P-001", captor.getValue().getBizId());
        verify(orderMapper).update(isNull(), any());
    }

    @Test
    void duplicatePaySuccessShouldNotTransitionAgain() {
        when(orderMapper.selectOne(any())).thenReturn(waitPayOrder());
        doThrow(new DuplicateKeyException("duplicate"))
                .when(idempotentMapper).insert(any(Idempotent.class));

        orderService.processPaySuccess(paySuccessMessage());

        verify(orderMapper, never()).update(isNull(), any());
    }

    @Test
    void alreadyPaidOrderShouldBeIdempotent() {
        SeckillOrder order = waitPayOrder();
        order.setOrderStatus(OrderConstants.STATUS_PAY_SUCCESS);
        when(orderMapper.selectOne(any())).thenReturn(order);

        orderService.processPaySuccess(paySuccessMessage());

        verify(idempotentMapper).insert(any(Idempotent.class));
        verify(orderMapper, never()).update(isNull(), any());
    }

    @Test
    void amountMismatchShouldBeRejectedBeforeIdempotencyInsert() {
        when(orderMapper.selectOne(any())).thenReturn(waitPayOrder());
        PaySuccessMessage message = paySuccessMessage();
        message.setAmount(new BigDecimal("88.00"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.processPaySuccess(message));

        assertEquals(ErrorCode.PARAM_ERROR, e.getErrorCode());
        verify(idempotentMapper, never()).insert(any(Idempotent.class));
    }

    @Test
    void terminalOrderShouldNotBeOverwrittenByPaySuccess() {
        SeckillOrder order = waitPayOrder();
        order.setOrderStatus(OrderConstants.STATUS_TIMEOUT);
        when(orderMapper.selectOne(any())).thenReturn(order);

        orderService.processPaySuccess(paySuccessMessage());

        verify(idempotentMapper).insert(any(Idempotent.class));
        verify(orderMapper, never()).update(isNull(), any());
    }

    @Test
    void paySuccessCasConflictShouldRetryWhenOrderStillWaitPay() {
        SeckillOrder first = waitPayOrder();
        SeckillOrder latest = waitPayOrder();
        latest.setVersion(3);
        when(orderMapper.selectOne(any())).thenReturn(first, latest);
        when(orderMapper.update(isNull(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> orderService.processPaySuccess(paySuccessMessage()));
    }

    @Test
    void cancelShouldTransitionAndReleaseActiveKey() {
        when(orderMapper.selectOne(any())).thenReturn(waitPayOrder());
        when(orderMapper.update(isNull(), any())).thenReturn(1);

        SeckillOrder order = orderService.prepareCancel(10001L, "123");
        assertNotNull(order);
        verify(orderMapper).update(isNull(), any());
    }

    @Test
    void cancelWrongOwnerShouldThrow() {
        when(orderMapper.selectOne(any())).thenReturn(waitPayOrder());
        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.prepareCancel(99999L, "123"));
        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
    }

    @Test
    void cancelNotWaitPayShouldThrow() {
        SeckillOrder order = waitPayOrder();
        order.setOrderStatus("PAY_SUCCESS");
        when(orderMapper.selectOne(any())).thenReturn(order);
        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.prepareCancel(10001L, "123"));
        assertEquals(ErrorCode.ORDER_STATUS_INVALID, e.getErrorCode());
    }

    @Test
    void publishCancelNotifyShouldSendAndMarkSent() {
        when(cancelOrderProducer.send(any(CancelOrderMessage.class))).thenReturn(true);
        when(orderMapper.update(isNull(), any())).thenReturn(1);

        SeckillOrder order = waitPayOrder();
        order.setOrderStatus("TIMEOUT");
        orderService.publishCancelNotify(order);

        ArgumentCaptor<CancelOrderMessage> captor = ArgumentCaptor.forClass(CancelOrderMessage.class);
        verify(cancelOrderProducer).send(captor.capture());
        assertEquals("123", captor.getValue().getOrderId());
        assertEquals("TIMEOUT", captor.getValue().getReason());
        verify(orderMapper).update(isNull(), any());
    }

    @Test
    void publishCancelNotifyFailureShouldNotMarkSent() {
        when(cancelOrderProducer.send(any(CancelOrderMessage.class))).thenReturn(false);
        orderService.publishCancelNotify(waitPayOrder());
        verify(orderMapper, never()).update(isNull(), any());
    }

    @Test
    void getOrderDetailShouldReturnOrderWithItems() {
        when(orderMapper.selectOne(any())).thenReturn(waitPayOrder());
        when(orderItemMapper.selectList(any())).thenReturn(List.of(new OrderItem()));
        var response = orderService.getOrderDetail(10001L, "123");
        assertEquals("123", response.getOrderNo());
        assertEquals(1, response.getItems().size());
    }

    @Test
    void listOrdersShouldMapPage() {
        Page<SeckillOrder> page = new Page<>(1, 10);
        page.setRecords(List.of(waitPayOrder()));
        page.setTotal(1);
        when(orderMapper.selectPage(any(), any())).thenReturn(page);

        var result = orderService.listOrders(10001L, null, 1, 10);
        assertEquals(1, result.getTotal());
        assertEquals("123", result.getList().get(0).getOrderNo());
    }
}
