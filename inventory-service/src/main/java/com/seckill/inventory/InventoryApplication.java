package com.seckill.inventory;

import com.seckill.inventory.config.InventoryProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = {"com.seckill.inventory", "com.seckill.common"})
@MapperScan("com.seckill.inventory.mapper")
@EnableConfigurationProperties(InventoryProperties.class)
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
