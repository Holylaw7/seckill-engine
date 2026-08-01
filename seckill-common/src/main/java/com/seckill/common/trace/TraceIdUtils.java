package com.seckill.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * TraceId 工具：生成、获取、清理。
 *
 * <p>基于 ThreadLocal，使用完毕必须 {@link #clear()}，避免线程池污染。</p>
 */
public final class TraceIdUtils {

    private static final int TRACE_ID_LENGTH = 32;
    private static final String MDC_KEY = "traceId";

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceIdUtils() {
    }

    /**
     * 生成 32 位十六进制 TraceId。
     */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 设置当前线程 TraceId（同时写入 MDC，便于日志关联）。
     */
    public static void set(String traceId) {
        TRACE_ID.set(traceId);
        try {
            MDC.put(MDC_KEY, traceId);
        } catch (Throwable ignored) {
            // MDC 不可用时不影响业务
        }
    }

    /**
     * 获取当前线程 TraceId，未设置时返回 null。
     */
    public static String get() {
        return TRACE_ID.get();
    }

    /**
     * 获取当前 TraceId，不存在则生成并绑定。
     */
    public static String getOrCreate() {
        String traceId = TRACE_ID.get();
        if (traceId == null || traceId.isEmpty()) {
            traceId = generate();
            set(traceId);
        }
        return traceId;
    }

    /**
     * 清理当前线程 TraceId，必须在请求结束或任务执行完毕后调用。
     */
    public static void clear() {
        TRACE_ID.remove();
        try {
            MDC.remove(MDC_KEY);
        } catch (Throwable ignored) {
            // MDC 不可用时不影响业务
        }
    }

    public static int getTraceIdLength() {
        return TRACE_ID_LENGTH;
    }
}
