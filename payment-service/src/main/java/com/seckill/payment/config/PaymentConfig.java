package com.seckill.payment.config;

import com.seckill.common.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentConfig {

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(@Value("${seckill.payment.worker-id:5}") long workerId) {
        return new SnowflakeIdGenerator(workerId);
    }
}
