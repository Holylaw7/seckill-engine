package com.seckill.inventory.config;

import com.seckill.common.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class InventoryConfig {

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(@Value("${seckill.inventory.worker-id:3}") long workerId) {
        return new SnowflakeIdGenerator(workerId);
    }

    @Bean
    public RestClient recoverRestClient(InventoryProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.getRecover().getBaseUrl())
                .build();
    }
}
