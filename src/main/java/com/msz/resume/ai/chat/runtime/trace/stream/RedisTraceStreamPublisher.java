package com.msz.resume.ai.chat.runtime.trace.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msz.resume.ai.chat.runtime.trace.TimelineActionPayloadProjector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
/**
 * Redis Trace Stream 发布器。
 *
 * 作用：把可持久化的 timeline action 写进 Redis Stream，
 * 供后台消费者异步落库、回放或排障查询使用。
 * 可以把它理解成“Trace 中转站发件人”，把前台实时事件同步投递到后方队列。
 *
 * 代码逻辑：
 * 1. 先判断 Trace Stream 是否启用以及 sessionId 是否有效
 * 2. 用 TimelineActionPayloadProjector 把原始事件投影成标准 action
 * 3. 过滤掉敏感或不应落库的事件
 * 4. 补齐 eventId、runId、sequence、payloadJson 等字段后写入 Redis Stream
 */
@Component
public class RedisTraceStreamPublisher implements TraceStreamPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TraceStreamProperties properties;
    private final TimelineActionPayloadProjector payloadProjector;

    /** 创建 Redis Stream 发布器，负责把 timeline action 发往异步持久化链路。 */
    public RedisTraceStreamPublisher(StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper,
                                     TraceStreamProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.payloadProjector = new TimelineActionPayloadProjector();
    }

    @Override
    /** 发布一条可持久化的 timeline 事件到 Redis Stream。 */
    public void publishTimelineEvent(String sessionId, String eventType, long sequence, Map<String, Object> payload) {
        if (!properties.isEnabled() || sessionId == null || sessionId.isBlank()) {
            return;
        }

        try {
            Map<String, Object> action = payloadProjector.project(eventType, sequence, payload)
                    .filter(payloadProjector::isPersistable)
                    .orElse(null);
            if (action == null) {
                return;
            }

            String runId = stringValue(action.get("runId"));
            String actionId = stringValue(action.get("id"));
            long firstSequence = longValue(action.get("firstSequence"), sequence);
            String eventId = stableEventId(sessionId, runId, sequence, actionId);
            Instant createdAt = Instant.now();

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("eventId", eventId);
            fields.put("sessionId", sessionId);
            fields.put("runId", runId);
            fields.put("eventType", eventType);
            fields.put("actionId", actionId);
            fields.put("sequence", String.valueOf(sequence));
            fields.put("firstSequence", String.valueOf(firstSequence));
            fields.put("anchorMessageIndex", "-1");
            fields.put("createdAt", createdAt.toString());
            fields.put("payloadJson", objectMapper.writeValueAsString(action));

            StreamOperations<String, String, String> streamOps = redisTemplate.opsForStream();
            RedisStreamCommands.XAddOptions options = RedisStreamCommands.XAddOptions.maxlen(Math.max(1L, properties.getMaxLen()))
                    .approximateTrimming(properties.isApproximateTrim());
            RecordId recordId = streamOps.add(properties.getStreamKey(), fields, options);
            log.debug("[TraceStream] published event: streamId={}, eventId={}, sessionId={}, type={}, actionId={}, sequence={}",
                    recordId != null ? recordId.getValue() : null, eventId, sessionId, eventType, actionId, sequence);
        } catch (Exception e) {
            log.warn("[TraceStream] publish failed: sessionId={}, type={}, sequence={}, error={}",
                    sessionId, eventType, sequence, e.getMessage());
        }
    }

    /** 生成稳定事件主键，方便回放和去重时精确识别同一条动作。 */
    private static String stableEventId(String sessionId, String runId, long sequence, String actionId) {
        String safeRunId = runId != null && !runId.isBlank() ? runId : "no_run";
        String safeActionId = actionId != null && !actionId.isBlank() ? actionId : "no_action";
        return sessionId + ":" + safeRunId + ":" + sequence + ":" + safeActionId;
    }

    /** 安全读取字符串字段。 */
    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    /** 安全读取 long 字段，解析失败时退回兜底值。 */
    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value != null ? Long.parseLong(String.valueOf(value)) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
