package com.seckill.payment.state;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.payment.entity.PaymentOrder;
import com.seckill.payment.mapper.PaymentOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
    }
}
