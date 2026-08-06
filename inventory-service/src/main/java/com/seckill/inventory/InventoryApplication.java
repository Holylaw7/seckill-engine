package com.seckill.inventory;

import com.seckill.inventory.config.InventoryProperties;
import com.seckill.inventory.config.InventoryShardingProperties;
import com.seckill.inventory.config.InternalAuthProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.seckill.inventory", "com.seckill.common"})
@MapperScan("com.seckill.inventory.mapper")
@EnableConfigurationProperties({InventoryProperties.class, InventoryShardingProperties.class,
        InternalAuthProperties.class})
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
