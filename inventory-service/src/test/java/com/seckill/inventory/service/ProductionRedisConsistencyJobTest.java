package com.seckill.inventory.service;

import com.seckill.inventory.config.InventoryProperties;
import com.seckill.inventory.service.RedisStockValidationJob.RedisStockValidationReport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 6.8 ProductionRedisConsistencyJob：默认关闭；开启后只报警计数，不自动修复。
 */
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class ProductionRedisConsistencyJobTest {

    @Mock
    private RedisStockValidationJob validationJob;

    @Test
    void disabledByDefaultShouldNotRunValidation() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InventoryProperties properties = new InventoryProperties();
        ProductionRedisConsistencyJob job =
                new ProductionRedisConsistencyJob(validationJob, properties, registry);

        job.checkConfiguredSkus();

        verify(validationJob, never()).validate(org.mockito.ArgumentMatchers.anyLong());
        assertThat(failCount(registry)).isZero();
    }

    @Test
    void enabledWithPassingValidationShouldNotAlert() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InventoryProperties properties = new InventoryProperties();
        properties.getConsistencyCheck().setEnabled(true);
        properties.getConsistencyCheck().setSkuIds(List.of(20001L));
        when(validationJob.validate(20001L)).thenReturn(
                new RedisStockValidationReport(20001L, 1000L, 1000L, 1000L, 1000L, 0L, 1000L, List.of()));
        ProductionRedisConsistencyJob job =
                new ProductionRedisConsistencyJob(validationJob, properties, registry);

        job.checkConfiguredSkus();

        assertThat(failCount(registry)).isZero();
    }

    @Test
    void enabledWithDiffShouldIncrementCriticalAlertCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InventoryProperties properties = new InventoryProperties();
        properties.getConsistencyCheck().setEnabled(true);
        properties.getConsistencyCheck().setSkuIds(List.of(20001L));
        when(validationJob.validate(20001L)).thenReturn(
                new RedisStockValidationReport(20001L, 1000L, 900L, 1000L, 1000L, 0L, 1000L,
                        List.of("Redis global(1000) != SUM(redis bucket)(900)")));
        ProductionRedisConsistencyJob job =
                new ProductionRedisConsistencyJob(validationJob, properties, registry);

        job.checkConfiguredSkus();

        assertThat(failCount(registry)).isEqualTo(1);
    }

    private static double failCount(SimpleMeterRegistry registry) {
        io.micrometer.core.instrument.Counter counter =
                registry.find("inventory_redis_consistency_fail_total").counter();
        return counter == null ? 0 : counter.count();
    }
}
