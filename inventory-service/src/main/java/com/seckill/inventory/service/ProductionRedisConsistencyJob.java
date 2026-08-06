package com.seckill.inventory.service;

import com.seckill.inventory.config.InventoryProperties;
import com.seckill.inventory.service.RedisStockValidationJob.RedisStockValidationReport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 6.8 生产 Redis 一致性校验 Job（1 分钟周期）。
 *
 * <p>校验 Redis global stock == SUM(bucket stock) == MySQL available；
 * 异常只报警（metrics + ERROR 日志），禁止自动修复。</p>
 */
@Component
@RequiredArgsConstructor
public class ProductionRedisConsistencyJob {

    private static final Logger log = LoggerFactory.getLogger(ProductionRedisConsistencyJob.class);

    private final RedisStockValidationJob validationJob;
    private final InventoryProperties properties;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${seckill.inventory.consistency-check.interval-ms:60000}")
    public void checkConfiguredSkus() {
        if (!properties.getConsistencyCheck().isEnabled()) {
            return;
        }
        for (Long skuId : properties.getConsistencyCheck().getSkuIds()) {
            RedisStockValidationReport report = validationJob.validate(skuId);
            if (!report.isPass()) {
                Counter.builder("inventory_redis_consistency_fail_total")
                        .tag("application", "inventory-service")
                        .tag("skuId", String.valueOf(skuId))
                        .register(meterRegistry)
                        .increment();
                log.error("PRODUCTION REDIS CONSISTENCY CRITICAL skuId={} diffs={} "
                                + "(只报警，禁止自动修复；阻塞 Canary 升级)", skuId, report.diffs());
            }
        }
    }
}
