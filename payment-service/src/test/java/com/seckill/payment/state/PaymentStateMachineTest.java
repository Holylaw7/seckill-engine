package com.seckill.payment.state;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.payment.constant.PaymentConstants;
import com.seckill.payment.entity.PaymentOrder;
import com.seckill.payment.mapper.PaymentOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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
class PaymentStateMachineTest {

    @Mock
    private PaymentOrderMapper paymentOrderMapper;

    private PaymentStateMachine newMachine() {
        return new PaymentStateMachine(paymentOrderMapper);
    }

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PaymentOrder.class);
    }

    @Test
    void canTransitionShouldFollowFrozenMatrix() {
        PaymentStateMachine machine = newMachine();
        assertTrue(machine.canTransition("CREATE", "WAIT_PAY"));
        assertTrue(machine.canTransition("WAIT_PAY", "PAY_SUCCESS"));
        assertTrue(machine.canTransition("WAIT_PAY", "PAY_FAILED"));
        assertTrue(machine.canTransition("PAY_SUCCESS", "REFUNDING"));
        assertTrue(machine.canTransition("REFUNDING", "REFUND_SUCCESS"));

        assertFalse(machine.canTransition("WAIT_PAY", "CREATE"));
        assertFalse(machine.canTransition("PAY_SUCCESS", "PAY_FAILED"));
        assertFalse(machine.canTransition("REFUNDING", "PAY_SUCCESS"));
        assertFalse(machine.canTransition("REFUND_SUCCESS", "REFUNDING"));
    }

    @Test
    void transitionShouldSucceedWithCas() {
        PaymentOrder payment = new PaymentOrder();
        payment.setId(1L);
        payment.setStatus("WAIT_PAY");
        payment.setVersion(2);
        when(paymentOrderMapper.update(isNull(), any())).thenReturn(1);

        assertTrue(newMachine().transition(payment, "PAY_SUCCESS", "TXN001", null));
        verify(paymentOrderMapper).update(isNull(), any());
    }

    @Test
    void transitionConflictShouldReturnFalse() {
        PaymentOrder payment = new PaymentOrder();
        payment.setId(1L);
        payment.setStatus("WAIT_PAY");
        payment.setVersion(2);
        when(paymentOrderMapper.update(isNull(), any())).thenReturn(0);

        assertFalse(newMachine().transition(payment, "PAY_FAILED", null, null));
    }

    @Test
    void illegalTransitionShouldThrow() {
        PaymentOrder payment = new PaymentOrder();
        payment.setId(1L);
        payment.setStatus("PAY_SUCCESS");
        payment.setVersion(0);
        BusinessException e = assertThrows(BusinessException.class,
                () -> newMachine().transition(payment, "PAY_FAILED", null, null));
        assertTrue(e.getErrorCode() == ErrorCode.ORDER_STATUS_INVALID);
        verify(paymentOrderMapper, never()).update(isNull(), any());
        assertEquals(0, payment.getVersion());
    }

    @Test
    void should_reject_duplicate_payment_when_already_pay_success() {
        // Arrange
        PaymentOrder payment = new PaymentOrder();
        payment.setId(1L);
        payment.setStatus(PaymentConstants.STATUS_PAY_SUCCESS);
        payment.setVersion(3);

        // Act
        BusinessException e = assertThrows(BusinessException.class,
                () -> newMachine().transition(payment, PaymentConstants.STATUS_PAY_SUCCESS, "TXN001", null));

        // Assert
        assertEquals(ErrorCode.ORDER_STATUS_INVALID, e.getErrorCode());
        verify(paymentOrderMapper, never()).update(isNull(), any());
        assertEquals(PaymentConstants.STATUS_PAY_SUCCESS, payment.getStatus());
        assertEquals(3, payment.getVersion());
    }

    @Test
    void should_reject_duplicate_refund_when_already_refund_success() {
        // Arrange
        PaymentOrder payment = new PaymentOrder();
        payment.setId(1L);
        payment.setStatus(PaymentConstants.STATUS_REFUND_SUCCESS);
        payment.setVersion(4);

        // Act
        BusinessException e = assertThrows(BusinessException.class,
                () -> newMachine().transition(payment, PaymentConstants.STATUS_REFUND_SUCCESS, null, null));

        // Assert
        assertEquals(ErrorCode.ORDER_STATUS_INVALID, e.getErrorCode());
        verify(paymentOrderMapper, never()).update(isNull(), any());
        assertEquals(PaymentConstants.STATUS_REFUND_SUCCESS, payment.getStatus());
        assertEquals(4, payment.getVersion());
    }

    @Test
    void should_reject_illegal_transition_when_skip_payment_state() {
        // Arrange
        PaymentStateMachine machine = newMachine();
        List<String[]> illegalTransitions = List.of(
                new String[]{PaymentConstants.STATUS_CREATE, PaymentConstants.STATUS_PAY_SUCCESS},
                new String[]{PaymentConstants.STATUS_CREATE, PaymentConstants.STATUS_REFUNDING},
                new String[]{PaymentConstants.STATUS_WAIT_PAY, PaymentConstants.STATUS_REFUND_SUCCESS},
                new String[]{PaymentConstants.STATUS_PAY_FAILED, PaymentConstants.STATUS_REFUND_SUCCESS});

        for (String[] pair : illegalTransitions) {
            PaymentOrder payment = new PaymentOrder();
            payment.setId(1L);
            payment.setStatus(pair[0]);
            payment.setVersion(2);

            // Act
            BusinessException e = assertThrows(BusinessException.class,
                    () -> machine.transition(payment, pair[1], null, null));

            // Assert
            assertEquals(ErrorCode.ORDER_STATUS_INVALID, e.getErrorCode());
            verify(paymentOrderMapper, never()).update(isNull(), any());
            assertEquals(pair[0], payment.getStatus());
            assertEquals(2, payment.getVersion());
        }
    }
}
