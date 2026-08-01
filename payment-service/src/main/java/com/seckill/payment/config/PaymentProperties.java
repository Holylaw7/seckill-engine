package com.seckill.payment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "seckill.payment")
public class PaymentProperties {

    private long workerId = 5L;

    private final Callback callback = new Callback();

    private final Channel channel = new Channel();

    private final RefundCompensate refundCompensate = new RefundCompensate();

    @Data
    public static class Callback {
        /** 回调时间窗口（秒），默认 300 */
        private int windowSeconds = 300;
        /** IP 白名单预留（空=不启用） */
        private String ipWhitelist = "";
    }

    @Data
    public static class Channel {
        private final Mock mock = new Mock();

        @Data
        public static class Mock {
            private String secret = "mock-channel-secret";
            private boolean refundFail = false;
        }
    }

    @Data
    public static class RefundCompensate {
        private int periodSeconds = 60;
        private int batchSize = 100;
    }
}
