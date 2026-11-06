package com.mindbase.utils;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 请求级 traceId（P1c 可观测性）：
 * 入口（WS/HTTP）生成或复用前端携带的 traceId，写入 MDC 覆盖整个请求处理；
 * Kafka 生产侧写入消息头、消费侧取回同一值，跨线程/跨进程贯穿日志；
 * 每次处理结束必须 {@link #clear()}，防止线程池复用线程时残留上一请求的 traceId。
 * <p>
 * 最小实现：只依赖 slf4j MDC，不引入 Micrometer Tracing。
 */
public final class TraceContext {

    /** MDC key，与 logback pattern 的 %X{traceId} 对应 */
    public static final String MDC_KEY = "traceId";
    /** Kafka 消息头名，生产侧写入、消费侧读取 */
    public static final String KAFKA_HEADER = "X-Trace-Id";

    private TraceContext() {
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString();
    }

    /** 当前 traceId，缺失或为空时生成新的 */
    public static String currentOrNew() {
        String current = current();
        return current == null || current.isBlank() ? newTraceId() : current;
    }

    /** 当前 traceId，可能为 null */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** 客户端提供的 traceId 白名单：UUID/十六进制形态，防日志注入（CWE-117）与超长撑爆日志 */
    private static final java.util.regex.Pattern SAFE_TRACE_ID = java.util.regex.Pattern.compile("^[0-9a-fA-F-]{8,64}$");

    /** 仅在 traceId 通过白名单（UUID/十六进制形态）时写入 MDC；不合规值一律换新生成的安全 id */
    public static void put(String traceId) {
        if (traceId != null && SAFE_TRACE_ID.matcher(traceId).matches()) {
            MDC.put(MDC_KEY, traceId);
        } else {
            MDC.put(MDC_KEY, newTraceId());
        }
    }

    /** 清理 MDC，防线程池污染 */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
