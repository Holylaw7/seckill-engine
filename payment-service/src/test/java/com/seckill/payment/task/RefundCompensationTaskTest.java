package com.seckill.payment.task;

import com.seckill.payment.config.PaymentProperties;
import com.seckill.payment.entity.PaymentRefund;
import com.seckill.payment.service.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundCompensationTaskTest {

    @Mock
    private RefundService refundService;

    private RefundCompensationTask task;

    @BeforeEach
    void setUp() {
        PaymentProperties properties = new PaymentProperties();
        properties.getRefundCompensate().setBatchSize(100);
        task = new RefundCompensationTask(refundService, properties);
    }

    @Test
    void shouldRetryFailedRefunds() {
        PaymentRefund refund = new PaymentRefund();
        refund.setRefundNo("R1");
        when(refundService.findRefundFailures(100)).thenReturn(List.of(refund));

        task.compensateRefundFailures();
        verify(refundService).retryRefund("R1");
    }

    @Test
    void retryFailureShouldBeSwallowed() {
        PaymentRefund refund = new PaymentRefund();
        refund.setRefundNo("R1");
        when(refundService.findRefundFailures(100)).thenReturn(List.of(refund));
        doThrow(new RuntimeException("channel down")).when(refundService).retryRefund("R1");

        task.compensateRefundFailures();
    }
}
