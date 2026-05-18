package com.msz.resume.ai.chat.runtime.trace;

import com.msz.resume.ai.chat.api.dto.ChatStreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 对话事件发送器。
 *
 * 作用：把后端内部事件统一封装成结构化的 SSE 数据包发给前端，
 * 同时顺手记录时间线动作和发送序号。
 * 可以把它理解成“前端事件出站口”，所有流式事件都要先从这里过一遍。
 *
 * 代码逻辑：
 * 1. 持有 SseEmitter、ObjectMapper、sessionId 和时间线记录器
 * 2. 每次发送时生成统一的 sequence 和 timestamp
 * 3. 将事件序列化为 ChatStreamEvent 后发往前端
 * 4. 发送失败时关闭连接状态，避免后续继续硬发
 */
@Slf4j
public class ChatStreamEventSink {

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;
    private final String sessionId;
    private final TimelineActionRecorder timelineActionRecorder;
    private final AtomicLong sequence = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 创建默认版本的事件出口，不额外记录 timeline 动作。 */
    public ChatStreamEventSink(SseEmitter emitter, ObjectMapper objectMapper, String sessionId) {
        this(emitter, objectMapper, sessionId, TimelineActionRecorder.noop());
    }

    /** 创建完整版本的事件出口，同时接入时间线动作记录器。 */
    public ChatStreamEventSink(SseEmitter emitter,
                               ObjectMapper objectMapper,
                               String sessionId,
                               TimelineActionRecorder timelineActionRecorder) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
        this.sessionId = sessionId;
        this.timelineActionRecorder = timelineActionRecorder != null ? timelineActionRecorder : TimelineActionRecorder.noop();
        this.emitter.onCompletion(() -> closed.set(true));
        this.emitter.onTimeout(() -> closed.set(true));
        this.emitter.onError(error -> closed.set(true));
    }

    /** 发送一个结构化 SSE 事件，并给它补上统一的 sequence、timestamp 和 replay 数据。 */
    public synchronized void send(String type, Map<String, Object> payload) throws IOException {
        if (closed.get()) {
            return;
        }

        long nextSequence = sequence.incrementAndGet();
        Map<String, Object> eventPayload = payload != null ? payload : Map.of();
        ChatStreamEvent event = ChatStreamEvent.builder()
                .type(type)
                .sessionId(sessionId)
                .sequence(nextSequence)
                .timestamp(Instant.now())
                .payload(eventPayload)
                .build();

        try {
            emitter.send(SseEmitter.event()
                    .name(type)
                    .data(objectMapper.writeValueAsString(event)));
            timelineActionRecorder.record(type, nextSequence, eventPayload);
            if (isHighFrequencyEvent(type)) {
                log.debug("[ChatStreamEventSink] SSE sent: type={}, sessionId={}, sequence={}, runId={}, id={}, parentId={}, kind={}, status={}, title={}",
                        type,
                        sessionId,
                        event.getSequence(),
                        value(payload, "runId"),
                        value(payload, "id"),
                        value(payload, "parentId"),
                        value(payload, "kind"),
                        value(payload, "status"),
                        value(payload, "title"));
            } else {
                log.info("[ChatStreamEventSink] SSE sent: type={}, sessionId={}, sequence={}, runId={}, id={}, parentId={}, kind={}, status={}, title={}",
                        type,
                        sessionId,
                        event.getSequence(),
                        value(payload, "runId"),
                        value(payload, "id"),
                        value(payload, "parentId"),
                        value(payload, "kind"),
                        value(payload, "status"),
                        value(payload, "title"));
            }
        } catch (JsonProcessingException e) {
            throw new IOException("流式事件序列化失败", e);
        } catch (IOException | RuntimeException e) {
            closed.set(true);
            throw e;
        }
    }

    /** 发送统一格式的错误事件，前端可以据此直接展示错误状态。 */
    public void error(String code, String message) {
        try {
            send("error", Map.of(
                    "code", code,
                    "message", message != null ? message : "",
                    "recoverable", false
            ));
        } catch (Exception e) {
            log.warn("[ChatStreamEventSink] error 事件发送失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /** 正常结束 SSE 连接，相当于告诉前端“这轮流式输出已经播完了”。 */
    public void complete() {
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    /** 以错误方式结束 SSE 连接，让上层知道这次是异常收尾。 */
    public void completeWithError(Throwable error) {
        if (closed.compareAndSet(false, true)) {
            emitter.completeWithError(error);
        }
    }

    /** 返回连接是否已关闭，给上游决定还要不要继续发事件。 */
    public boolean isClosed() {
        return closed.get();
    }

    /** 从 payload 里安全取一个字段，只做日志拼装，不参与业务判断。 */
    private static Object value(Map<String, Object> payload, String key) {
        return payload != null ? payload.get(key) : null;
    }

    /** 判断事件是不是高频事件，高频事件走 debug 日志，避免日志刷屏。 */
    private static boolean isHighFrequencyEvent(String type) {
        return "message_delta".equals(type);
    }
}
