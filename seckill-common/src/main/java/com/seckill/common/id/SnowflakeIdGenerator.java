package com.seckill.common.id;

import java.time.Clock;
import java.util.Objects;

/**
 * Snowflake ID 生成器。
 *
 * <p>结构：1 bit 符号位 + 41 bit 毫秒时间戳 + 10 bit workerId + 12 bit 序列号。</p>
 *
 * <p>时间回拨策略（冻结）：回拨 &lt; 5ms 自旋等待；回拨 ≥ 5ms 快速失败（抛出异常并告警），禁止继续生成。</p>
 */
public class SnowflakeIdGenerator {

    /** 起始时间戳：2024-01-01T00:00:00Z */
    private static final long EPOCH = 1704067200000L;

    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    /** 最大允许回拨毫秒数（冻结） */
    private static final long MAX_BACKWARD_MS = 5L;

    private final long workerId;
    private final Clock clock;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long workerId) {
        this(workerId, Clock.systemUTC());
    }

    public SnowflakeIdGenerator(long workerId, Clock clock) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be in [0, 1023], actual: " + workerId);
        }
        this.workerId = workerId;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 生成下一个全局唯一 ID（线程安全）。
     */
    public synchronized long nextId() {
        long timestamp = clock.millis();

        if (timestamp < lastTimestamp) {
            long backwardMs = lastTimestamp - timestamp;
            if (backwardMs > MAX_BACKWARD_MS) {
                throw new IllegalStateException(
                        "clock moved backwards more than " + MAX_BACKWARD_MS + "ms, backward="
                                + backwardMs + ", workerId=" + workerId);
            }
            try {
                Thread.sleep(backwardMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("clock backward wait interrupted, workerId=" + workerId, e);
            }
            timestamp = clock.millis();
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = tillNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long tillNextMillis(long lastTimestamp) {
        long timestamp = clock.millis();
        while (timestamp <= lastTimestamp) {
            timestamp = clock.millis();
        }
        return timestamp;
    }
}
