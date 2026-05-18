package com.msz.resume.ai.chat.runtime.trace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Timeline Action 载荷投影器。
 *
 * 作用：把 SSE 原始事件 payload 规整成前端时间线和后端落库都能复用的统一 action 结构。
 * 可以把它理解成“翻译层”，上游各种事件说法不一，到这里都被翻成统一方言。
 *
 * 代码逻辑：
 * 1. 先判断事件是否属于 timeline 关心的类型
 * 2. 对 ask_user_question / pending 这类特殊事件补齐标准字段
 * 3. 统一补上 kind、eventType、sequence、id、是否可持久化等元信息
 * 4. 给下游 recorder / Redis stream / DB projector 提供稳定输入
 */
public class TimelineActionPayloadProjector {

    /** 把原始 SSE 事件投影成标准 timeline action；不属于时间线的事件会被忽略。 */
    public Optional<Map<String, Object>> project(String eventType, long sequence, Map<String, Object> payload) {
        if (!isTimelineEvent(eventType) || payload == null) {
            return Optional.empty();
        }

        Map<String, Object> actionPayload = normalizePayload(eventType, sequence, payload);
        String id = stringValue(actionPayload.get("id"));
        if (id.isBlank()) {
            id = eventType + "_" + sequence;
        }

        Map<String, Object> action = new LinkedHashMap<>(actionPayload);
        action.put("kind", kindFor(eventType));
        action.put("eventType", eventType);
        action.put("sequence", sequence);
        action.put("id", id);
        action.putIfAbsent("firstSequence", sequence);
        action.putIfAbsent(TimelineActionService.FIELD_PROMPT_VISIBLE, false);
        action.putIfAbsent(TimelineActionService.FIELD_PERSISTABLE, true);
        action.putIfAbsent(TimelineActionService.FIELD_SENSITIVE, false);
        return Optional.of(action);
    }

    /** 判断这个 action 是否适合落库，敏感或显式不可持久化的内容会被过滤。 */
    public boolean isPersistable(Map<String, Object> action) {
        return action != null
                && booleanValue(action.getOrDefault(TimelineActionService.FIELD_PERSISTABLE, true))
                && !booleanValue(action.getOrDefault(TimelineActionService.FIELD_SENSITIVE, false));
    }

    /** 判断某类 SSE 事件是否属于前端时间线要消费的事件。 */
    public static boolean isTimelineEvent(String eventType) {
        return "assistant_checkpoint".equals(eventType)
                || "tool_use_started".equals(eventType)
                || "tool_use_delta".equals(eventType)
                || "tool_use_result".equals(eventType)
                || "tool_use_error".equals(eventType)
                || "artifact_ready".equals(eventType)
                || "delegation_started".equals(eventType)
                || "delegation_result".equals(eventType)
                || "delegation_error".equals(eventType)
                || "ask_user_question".equals(eventType)
                || "pending".equals(eventType);
    }

    /** 把底层事件类型映射成前端更容易消费的动作大类。 */
    public static String kindFor(String eventType) {
        return switch (eventType) {
            case "assistant_checkpoint" -> "checkpoint";
            case "artifact_ready" -> "artifact_ready";
            case "delegation_started", "delegation_result", "delegation_error" -> "delegation";
            case "ask_user_question", "pending" -> "user_question";
            default -> "tool_use";
        };
    }

    /** 规范化特殊事件载荷，尤其是等待用户回答这类 pending 场景。 */
    private static Map<String, Object> normalizePayload(String eventType, long sequence, Map<String, Object> payload) {
        if (!"ask_user_question".equals(eventType) && !"pending".equals(eventType)) {
            return payload;
        }

        Map<String, Object> action = new LinkedHashMap<>(payload);
        Object questions = payload.get("questions");
        int questionCount = questionCount(questions);
        String pendingId = stringValue(payload.get("pendingId"));
        String toolCallId = stringValue(payload.get("toolCallId"));
        String id = firstNonBlank(
                stringValue(payload.get("id")),
                pendingId.isBlank() ? "" : "user_question_" + pendingId,
                toolCallId.isBlank() ? "" : "user_question_" + toolCallId,
                "user_question_" + sequence
        );

        action.put("id", id);
        action.put("pendingId", pendingId);
        action.put("toolCallId", toolCallId);
        action.put("questions", questions);
        action.put(TimelineActionService.FIELD_PROMPT_VISIBLE, false);
        action.put(TimelineActionService.FIELD_PERSISTABLE, true);
        action.put(TimelineActionService.FIELD_SENSITIVE, false);
        action.put("title", firstNonBlank(stringValue(payload.get("title")), "需要你补充信息"));
        action.put("summary", firstNonBlank(stringValue(payload.get("summary")), questionSummary(questions, questionCount)));
        action.put("questionCount", questionCount);
        action.put("status", "pending");
        return action;
    }

    /** 统计问题数量，保证前端至少拿到一个合理的计数。 */
    private static int questionCount(Object questions) {
        if (questions instanceof List<?> list) {
            return Math.max(1, list.size());
        }
        return 1;
    }

    /** 给待回答问题生成简短摘要，方便列表态直接展示。 */
    private static String questionSummary(Object questions, int questionCount) {
        if (questionCount > 1) {
            return questionCount + " 个问题待回答";
        }
        if (questions instanceof List<?> list && !list.isEmpty()) {
            Object first = list.getFirst();
            if (first instanceof Map<?, ?> map) {
                String questionText = firstNonBlank(
                        stringValue(map.get("questionText")),
                        stringValue(map.get("question")),
                        stringValue(map.get("title"))
                );
                if (!questionText.isBlank()) {
                    return questionText;
                }
            }
        }
        return "等待你的回答";
    }

    /** 把各种类型的布尔值安全收口成 boolean。 */
    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    /** 安全读取字符串值，避免空指针到处传。 */
    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    /** 从一串候选值里挑第一个非空字符串。 */
    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
