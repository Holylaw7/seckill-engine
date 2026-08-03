package com.seckill.integration.load;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 压测报告输出：JSON + CSV，字段与 JMeter 对齐。
 */
public final class LoadReport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LoadReport() {
    }

    public static Path writeJson(String scenario, LoadMetrics metrics) throws IOException {
        return writeJson(scenario, metrics, defaultDir());
    }

    public static Path writeJson(String scenario, LoadMetrics metrics, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(scenario + ".json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), fields(scenario, metrics));
        return file;
    }

    public static Path writeCsv(String scenario, LoadMetrics metrics) throws IOException {
        return writeCsv(scenario, metrics, defaultDir());
    }

    public static Path writeCsv(String scenario, LoadMetrics metrics, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(scenario + ".csv");
        Files.writeString(file, csvLine(scenario, metrics));
        return file;
    }

    public static Path defaultDir() {
        return Path.of(System.getProperty(LoadConstants.LOAD_REPORT_DIR, "target/load-reports"));
    }

    private static Map<String, Object> fields(String scenario, LoadMetrics metrics) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scenario", scenario);
        fields.put("total", metrics.totalRequests());
        fields.put("success", metrics.successCount());
        fields.put("failed", metrics.failureCount());
        fields.put("successRate", round(metrics.successRate()));
        fields.put("qps", round(metrics.qps()));
        fields.put("avgRT", round(metrics.avgRtMs()));
        fields.put("p50", round(metrics.p50Ms()));
        fields.put("p95", round(metrics.p95Ms()));
        fields.put("p99", round(metrics.p99Ms()));
        fields.put("durationMs", metrics.durationMillis());
        fields.put("timestamp", Instant.ofEpochMilli(metrics.endTimeMillis()).toString());
        return fields;
    }

    private static String csvLine(String scenario, LoadMetrics metrics) {
        return String.join(",", "scenario,total,success,failed,qps,avgRT,p50,p95,p99,timestamp",
                String.join(",",
                        scenario,
                        String.valueOf(metrics.totalRequests()),
                        String.valueOf(metrics.successCount()),
                        String.valueOf(metrics.failureCount()),
                        String.valueOf(round(metrics.qps())),
                        String.valueOf(round(metrics.avgRtMs())),
                        String.valueOf(round(metrics.p50Ms())),
                        String.valueOf(round(metrics.p95Ms())),
                        String.valueOf(round(metrics.p99Ms())),
                        Instant.ofEpochMilli(metrics.endTimeMillis()).toString()));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
