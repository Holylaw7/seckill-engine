package com.seckill.seckill;

import com.seckill.seckill.config.SeckillProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = {"com.seckill.seckill", "com.seckill.common"})
@MapperScan("com.seckill.seckill.mapper")
@EnableConfigurationProperties(SeckillProperties.class)
public class SeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillApplication.class, args);
    }
}
