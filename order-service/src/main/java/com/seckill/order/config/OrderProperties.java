package com.seckill.order.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * order-service 配置（冻结：超时 15min、扫描 30s、batch 200、补偿 60s）。
 */
@Data
@ConfigurationProperties(prefix = "seckill.order")
public class OrderProperties {

    private long workerId = 4L;

    private int payDeadlineMinutes = 15;

    private final TimeoutClose timeoutClose = new TimeoutClose();

    private int cancelNotifyCompensatePeriodSeconds = 60;

    private final PreDeductConfirm preDeductConfirm = new PreDeductConfirm();

    @Data
    public static class TimeoutClose {
        private int periodSeconds = 30;
        private int batchSize = 200;
    }

    @Data
    public static class PreDeductConfirm {
        private String baseUrl = "http://localhost:8082";
        private String path = "/api/v1/seckill/internal/pre-deducts/confirm";
    }
}
