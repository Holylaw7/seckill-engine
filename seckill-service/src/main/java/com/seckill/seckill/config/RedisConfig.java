package com.seckill.seckill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class RedisConfig {

    @Bean("seckillDeductScript")
    public DefaultRedisScript<Long> seckillDeductScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/seckill_deduct.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean("seckillRecoverScript")
    public DefaultRedisScript<Long> seckillRecoverScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/seckill_recover.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
