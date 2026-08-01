package com.seckill.seckill.config;

import com.seckill.common.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class SeckillConfig {

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(@Value("${seckill.core.worker-id:2}") long workerId) {
        return new SnowflakeIdGenerator(workerId);
    }

    @Bean
    public RestClient riskRestClient(SeckillProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.getRiskCheck().getBaseUrl())
                .build();
    }
}
