package com.msz.resume.ai.chat.runtime.trace.stream;

import java.time.Instant;
import java.util.Map;

/**
 * Redis Trace Stream 里的标准事件模型。
 *
 * 作用：承接 Redis Stream 原始字段，给消费者、投影器、回放服务提供统一结构。
 * 可以把它理解成“Trace 事件信封”，字段齐了，后面每一层都按这个格式处理。
 */
public record TraceStreamEvent(
        String streamMessageId,
        String eventId,
        String sessionId,
        String runId,
        String eventType,
        String actionId,
        long sequence,
        long firstSequence,
        int anchorMessageIndex,
        Map<String, Object> payload,
        Instant createdAt
) {
}
