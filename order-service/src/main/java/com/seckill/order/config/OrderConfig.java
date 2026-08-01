package com.seckill.order.config;

import com.seckill.common.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class OrderConfig {

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(@Value("${seckill.order.worker-id:4}") long workerId) {
        return new SnowflakeIdGenerator(workerId);
    }

    @Bean
    public RestClient preDeductConfirmRestClient(OrderProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.getPreDeductConfirm().getBaseUrl())
                .build();
    }
}
