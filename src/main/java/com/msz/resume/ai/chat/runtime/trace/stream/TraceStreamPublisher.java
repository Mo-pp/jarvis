package com.msz.resume.ai.chat.runtime.trace.stream;

import java.util.Map;

/**
 * Trace Stream 发布接口。
 *
 * 作用：抽象“把 timeline 事件发到持久化流里”这件事，
 * 让上层 recorder 不关心底层到底写 Redis 还是别的消息通道。
 */
public interface TraceStreamPublisher {

    /** 发布一条 timeline 事件到异步持久化流。 */
    void publishTimelineEvent(String sessionId, String eventType, long sequence, Map<String, Object> payload);
}
