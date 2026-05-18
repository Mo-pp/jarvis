package com.msz.resume.ai.chat.runtime.trace.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msz.resume.ai.chat.session.entity.TimelineActionRecord;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

/**
 * TimelineActionRecord 投影器。
 *
 * 作用：把 Redis Stream 里的 TraceStreamEvent 转成数据库可落的 TimelineActionRecord 实体。
 * 可以把它理解成“入库翻译器”，把消息队列里的事件信封拆开，整理成表结构要的样子。
 *
 * 代码逻辑：
 * 1. 校验事件最基本的 sessionId、actionId、payload 是否齐全
 * 2. 从 payload 和 event 本体里提取 eventType、kind、status、sequence 等字段
 * 3. 序列化 payloadJson，并补上创建/更新时间
 * 4. 组装成 TimelineActionRecord 返回给消费者批量 upsert
 */
@Component
public class TimelineActionRecordProjector {

    private final ObjectMapper objectMapper;

    /** 创建数据库投影器，负责把流事件整理成可落表的实体。 */
    public TimelineActionRecordProjector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 把一条 TraceStreamEvent 投影成 TimelineActionRecord；不合法事件会被忽略。 */
    public Optional<TimelineActionRecord> project(TraceStreamEvent event) {
        if (event == null || event.sessionId() == null || event.sessionId().isBlank()
                || event.actionId() == null || event.actionId().isBlank()
                || event.payload() == null || event.payload().isEmpty()) {
            return Optional.empty();
        }

        try {
            TimelineActionRecord record = new TimelineActionRecord();
            record.setSessionId(event.sessionId());
            record.setActionId(event.actionId());
            record.setAnchorMessageIndex(event.anchorMessageIndex());
            record.setEventType(stringValue(event.payload().get("eventType"), event.eventType()));
            record.setKind(stringValue(event.payload().get("kind"), ""));
            record.setFirstSequence(event.firstSequence());
            record.setSequence(event.sequence());
            record.setStatus(stringValue(event.payload().get("status"), ""));
            record.setPayloadJson(objectMapper.writeValueAsString(event.payload()));
            record.setPromptVisible(booleanValue(event.payload().getOrDefault("promptVisible", false)));
            record.setPersistable(booleanValue(event.payload().getOrDefault("persistable", true)));
            LocalDateTime createdAt = LocalDateTime.ofInstant(event.createdAt(), ZoneId.systemDefault());
            record.setCreatedAt(createdAt);
            record.setUpdatedAt(LocalDateTime.now());
            return Optional.of(record);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /** 把 payload 里的布尔值安全归一化。 */
    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    /** 优先取实际值，没有时退回给定兜底值。 */
    private static String stringValue(Object value, String fallback) {
        return value != null ? String.valueOf(value) : fallback;
    }
}
