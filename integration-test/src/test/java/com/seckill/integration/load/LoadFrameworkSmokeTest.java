package com.seckill.integration.load;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 压测框架冒烟验证（不依赖容器、不依赖 Docker CLI）：
 * 并发执行、指标计算、报告输出、资源监控采样。
 */
class LoadFrameworkSmokeTest {

    @Test
    void executor_should_run_concurrently_and_metrics_should_be_consistent() throws Exception {
        LoadMetrics metrics = LoadTestExecutor.run(
                new LoadConfig(10, 100, Duration.ofSeconds(30)),
                index -> {
                    long sum = 0;
                    for (int i = 0; i < 10_000; i++) {
                        sum += i;
                    }
                    return sum >= 0;
                });

        assertThat(metrics.totalRequests()).isEqualTo(100);
        assertThat(metrics.successCount()).isEqualTo(100);
        assertThat(metrics.failureCount()).isZero();
        assertThat(metrics.successRate()).isEqualTo(100.0);
        assertThat(metrics.qps()).isGreaterThan(0);
        assertThat(metrics.avgRtMs()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.p99Ms()).isGreaterThanOrEqualTo(metrics.p50Ms());
        assertThat(metrics.durationMillis()).isGreaterThan(0);
    }

    @Test
    void executor_should_count_failures() throws Exception {
        LoadMetrics metrics = LoadTestExecutor.run(
                new LoadConfig(4, 10, Duration.ofSeconds(30)),
                index -> index % 2 == 0);

        assertThat(metrics.successCount()).isEqualTo(5);
        assertThat(metrics.failureCount()).isEqualTo(5);
        assertThat(metrics.successRate()).isEqualTo(50.0);
    }

    @Test
    void report_should_write_json_and_csv() throws Exception {
        LoadMetrics metrics = LoadTestExecutor.run(
                new LoadConfig(4, 20, Duration.ofSeconds(30)),
                index -> true);
        Path directory = Files.createTempDirectory("load-report");

        Path json = LoadReport.writeJson("smoke", metrics, directory);
        Path csv = LoadReport.writeCsv("smoke", metrics, directory);

        assertThat(json).exists();
        assertThat(csv).exists();
        String jsonContent = Files.readString(json);
        assertThat(jsonContent)
                .contains("\"scenario\"", "\"total\"", "\"success\"", "\"failed\"",
                        "\"qps\"", "\"avgRT\"", "\"p50\"", "\"p95\"", "\"p99\"", "\"timestamp\"");
        String csvContent = Files.readString(csv);
        assertThat(csvContent)
                .contains("scenario,total,success,failed,successRate,qps,avgRT,p50,p95,p99,duration,timestamp")
                .contains("smoke,20,20,0,100.0,");
    }

    @Test
    void resourceMonitor_should_sample_jvm_metrics() {
        ResourceMonitor monitor = ResourceMonitor.start(Duration.ofMillis(100));
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> monitor.samples().size() >= 3);
            ResourceMonitor.Sample sample = monitor.latest();
            assertThat(sample.heapUsedBytes()).isGreaterThan(0);
            assertThat(sample.threadCount()).isGreaterThan(0);
            assertThat(sample.gcCount()).isGreaterThanOrEqualTo(0);
        } finally {
            monitor.close();
        }
    }
}
