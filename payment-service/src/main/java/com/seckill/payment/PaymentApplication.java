package com.seckill.payment;

import com.seckill.payment.config.PaymentProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.seckill.payment", "com.seckill.common"})
@MapperScan("com.seckill.payment.mapper")
@EnableConfigurationProperties(PaymentProperties.class)
@EnableScheduling
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
