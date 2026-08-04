package com.seckill.order.state;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.order.constant.OrderConstants;
import com.seckill.order.entity.SeckillOrder;
import com.seckill.order.mapper.SeckillOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class OrderStateMachineTest {

    @Mock
    private SeckillOrderMapper orderMapper;

    private OrderStateMachine newMachine() {
        return new OrderStateMachine(orderMapper);
    }

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SeckillOrder.class);
    }

    @Test
    void canTransitionShouldFollowFrozenMatrix() {
        OrderStateMachine machine = newMachine();
        assertTrue(machine.canTransition("CREATE", "WAIT_PAY"));
        assertTrue(machine.canTransition("WAIT_PAY", "PAY_SUCCESS"));
        assertTrue(machine.canTransition("WAIT_PAY", "CANCEL"));
        assertTrue(machine.canTransition("WAIT_PAY", "TIMEOUT"));
        assertTrue(machine.canTransition("PAY_SUCCESS", "REFUND"));

        assertFalse(machine.canTransition("WAIT_PAY", "CREATE"));
        assertFalse(machine.canTransition("PAY_SUCCESS", "CANCEL"));
        assertFalse(machine.canTransition("TIMEOUT", "PAY_SUCCESS"));
        assertFalse(machine.canTransition("CANCEL", "REFUND"));
    }

    @Test
    void transitionShouldSucceedWithCas() {
        SeckillOrder order = new SeckillOrder();
        order.setId(1L);
        order.setOrderStatus("WAIT_PAY");
        order.setActiveKey("10001:30001:20001");
        order.setVersion(2);
        when(orderMapper.update(isNull(), any())).thenReturn(1);

        assertTrue(newMachine().transition(order, "CANCEL", "用户取消"));
        verify(orderMapper).update(isNull(), any());
    }

    @Test
    void transitionConflictShouldReturnFalse() {
        SeckillOrder order = new SeckillOrder();
        order.setId(1L);
        order.setOrderStatus("WAIT_PAY");
        order.setActiveKey("a:b:c");
        order.setVersion(2);
        when(orderMapper.update(isNull(), any())).thenReturn(0);

        assertFalse(newMachine().transition(order, "TIMEOUT", "支付超时关闭"));
    }

    @Test
    void illegalTransitionShouldThrow() {
        SeckillOrder order = new SeckillOrder();
        order.setId(1L);
        order.setOrderStatus("PAY_SUCCESS");
        order.setVersion(0);
        BusinessException e = assertThrows(BusinessException.class,
                () -> newMachine().transition(order, "CANCEL", "用户取消"));
        assertTrue(e.getErrorCode() == ErrorCode.ORDER_STATUS_INVALID);
        verify(orderMapper, never()).update(isNull(), any());
        assertEquals(0, order.getVersion());
    }

    @Test
    void should_keep_active_key_when_transition_to_pay_success() {
        // Arrange
        SeckillOrder order = new SeckillOrder();
        order.setId(1L);
        order.setOrderStatus(OrderConstants.STATUS_WAIT_PAY);
        order.setActiveKey("10001:30001:20001");
        order.setVersion(2);
        when(orderMapper.update(isNull(), any())).thenReturn(1);

        // Act
        boolean ok = newMachine().transition(order, OrderConstants.STATUS_PAY_SUCCESS, null);

        // Assert
        assertThat(ok).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<SeckillOrder>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(orderMapper).update(isNull(), captor.capture());
        Map<String, Object> pairs = captor.getValue().getParamNameValuePairs();
        assertThat(pairs).containsValue(OrderConstants.STATUS_PAY_SUCCESS);
        assertThat(pairs).containsValue("10001:30001:20001");
        assertThat(pairs).containsValue(3);
        assertThat(pairs).containsValue(null);
        assertThat(pairs).doesNotContainValue(OrderConstants.NOTIFY_PENDING);
    }

    @Test
    void should_reject_illegal_transition_when_status_not_allowed() {
        // Arrange
        OrderStateMachine machine = newMachine();
        List<String[]> illegalTransitions = List.of(
                new String[]{OrderConstants.STATUS_CREATE, OrderConstants.STATUS_PAY_SUCCESS},
                new String[]{OrderConstants.STATUS_WAIT_PAY, OrderConstants.STATUS_REFUND},
                new String[]{OrderConstants.STATUS_CANCEL, OrderConstants.STATUS_PAY_SUCCESS},
                new String[]{OrderConstants.STATUS_TIMEOUT, OrderConstants.STATUS_WAIT_PAY});

        for (String[] pair : illegalTransitions) {
            SeckillOrder order = new SeckillOrder();
            order.setId(1L);
            order.setOrderStatus(pair[0]);
            order.setActiveKey("10001:30001:20001");
            order.setVersion(2);

            // Act
            BusinessException e = assertThrows(BusinessException.class,
                    () -> machine.transition(order, pair[1], "test"));

            // Assert
            assertEquals(ErrorCode.ORDER_STATUS_INVALID, e.getErrorCode());
            verify(orderMapper, never()).update(isNull(), any());
            assertEquals(pair[0], order.getOrderStatus());
            assertEquals(2, order.getVersion());
        }
    }
}
