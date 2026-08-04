package com.seckill.order.config;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListenerBeanPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * order-service MQ 消费线程配置（Phase 6.1）：
 * 通过 seckill.mq.consumer.threads 覆盖 @RocketMQMessageListener 的 consumeThreadNumber，
 * 默认 20 与 rocketmq-spring 默认一致，行为不变。仅调整消费并发，不改变业务逻辑。
 */
@Configuration
public class MqConsumerConfig {

    @Bean
    public RocketMQMessageListenerBeanPostProcessor.AnnotationEnhancer mqConsumerThreadEnhancer(
            @Value("${seckill.mq.consumer.threads:20}") int threads) {
        return (attributes, element) -> {
            attributes.put("consumeThreadNumber", threads);
            attributes.put("consumeThreadMax", threads);
            return attributes;
        };
    }
}
