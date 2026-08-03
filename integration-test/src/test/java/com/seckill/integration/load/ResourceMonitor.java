package com.seckill.integration.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 资源监控：Docker stats（CPU/内存）+ JVM（Heap/Thread/GC），采样周期可配置。
 * Docker 指标不可用时以 -1 标记，不影响 JVM 采样。
 */
public final class ResourceMonitor implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Sample(
            long timestampMs,
            double cpuPercent,
            long memoryBytes,
            long heapUsedBytes,
            int threadCount,
            long gcCount,
            long gcTimeMs) {
    }

    private record DockerStats(double cpuPercent, long memoryBytes) {
        static DockerStats unavailable() {
            return new DockerStats(-1, -1);
        }
    }

    private final ScheduledExecutorService scheduler;
    private final List<String> containerIds;
    private final List<Sample> samples = new CopyOnWriteArrayList<>();
    private volatile Sample latest;

    private ResourceMonitor(Duration period, List<String> containerIds) {
        this.containerIds = containerIds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "load-resource-monitor");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleAtFixedRate(this::sample, 0, period.toMillis(), TimeUnit.MILLISECONDS);
    }

    public static ResourceMonitor start(Duration period, String... containerIds) {
        if (period == null || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("period must be positive");
        }
        return new ResourceMonitor(period, List.of(containerIds));
    }

    public List<Sample> samples() {
        return List.copyOf(samples);
    }

    public Sample latest() {
        return latest;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private void sample() {
        DockerStats docker = readDockerStats();
        long heapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += gc.getCollectionCount();
            gcTime += gc.getCollectionTime();
        }
        Sample sample = new Sample(System.currentTimeMillis(),
                docker.cpuPercent(), docker.memoryBytes(), heapUsed, threads, gcCount, gcTime);
        samples.add(sample);
        latest = sample;
    }

    private DockerStats readDockerStats() {
        if (containerIds.isEmpty()) {
            return DockerStats.unavailable();
        }
        Process process = null;
        try {
            List<String> command = new ArrayList<>(List.of(
                    "docker", "stats", "--no-stream", "--format", "{{json .}}"));
            command.addAll(containerIds);
            process = new ProcessBuilder(command).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return DockerStats.unavailable();
            }
            if (process.exitValue() != 0) {
                return DockerStats.unavailable();
            }
            double cpuSum = 0;
            long memorySum = 0;
            int count = 0;
            for (String line : output.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = MAPPER.readTree(line);
                cpuSum += parsePercent(node.path("CPUPerc").asText());
                memorySum += parseBytes(node.path("MemUsage").asText().split("/")[0].trim());
                count++;
            }
            if (count == 0) {
                return DockerStats.unavailable();
            }
            return new DockerStats(cpuSum / count, memorySum);
        } catch (Exception e) {
            return DockerStats.unavailable();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static double parsePercent(String value) {
        try {
            return Double.parseDouble(value.replace("%", "").trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static long parseBytes(String value) {
        try {
            String trimmed = value.trim();
            int split = 0;
            while (split < trimmed.length()
                    && (Character.isDigit(trimmed.charAt(split)) || trimmed.charAt(split) == '.')) {
                split++;
            }
            double number = Double.parseDouble(trimmed.substring(0, split));
            String unit = trimmed.substring(split).trim().toLowerCase();
            return switch (unit) {
                case "kib" -> (long) (number * 1024);
                case "mib" -> (long) (number * 1024 * 1024);
                case "gib" -> (long) (number * 1024 * 1024 * 1024);
                case "b" -> (long) number;
                default -> -1;
            };
        } catch (Exception e) {
            return -1;
        }
    }
}
