package com.seckill.common.trace;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@org.junit.jupiter.api.Tag("unit")
class TraceIdUtilsTest {

    @Test
    void generateShouldReturn32HexChars() {
        String traceId = TraceIdUtils.generate();
        assertEquals(TraceIdUtils.getTraceIdLength(), traceId.length());
        assertNotEquals(TraceIdUtils.generate(), traceId);
    }

    @Test
    void setGetClearShouldWorkInSameThread() {
        TraceIdUtils.set("trace-A");
        assertEquals("trace-A", TraceIdUtils.get());
        TraceIdUtils.clear();
        assertNull(TraceIdUtils.get());
    }

    @Test
    void getOrCreateShouldBindOnce() {
        String first = TraceIdUtils.getOrCreate();
        assertEquals(first, TraceIdUtils.getOrCreate());
        TraceIdUtils.clear();
    }

    @Test
    void threadLocalShouldNotLeakAcrossThreads() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> setter = pool.submit(() -> {
                TraceIdUtils.set("trace-pool-A");
                String value = TraceIdUtils.get();
                TraceIdUtils.clear();
                return value;
            });
            Future<String> reader = pool.submit(TraceIdUtils::get);
            assertEquals("trace-pool-A", setter.get(5, TimeUnit.SECONDS));
            assertNull(reader.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            TraceIdUtils.clear();
        }
    }
}
