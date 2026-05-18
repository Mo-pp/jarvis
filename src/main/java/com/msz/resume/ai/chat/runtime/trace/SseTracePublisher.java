package com.msz.resume.ai.chat.runtime.trace;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 基于 SSE 的 Trace 步骤发布器。
 *
 * 作用：把 StepTraceEvent 转成前端能直接消费的 `run_step` 事件，
 * 相当于把后端内部的步骤树翻译成前端的实时施工看板。
 */
@Slf4j
public class SseTracePublisher implements TracePublisher {

    private final ChatRunTraceContext traceContext;

    /** 绑定一次具体运行的 Trace 上下文，后续所有步骤都往这条 SSE 通道发。 */
    public SseTracePublisher(ChatRunTraceContext traceContext) {
        this.traceContext = traceContext;
    }

    @Override
    /** 发布单个步骤事件，并统一落成 `run_step` 结构给前端。 */
    public void publishStep(StepTraceEvent event) {
        if (traceContext == null || event == null || !traceContext.isActive()) {
            return;
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", event.id());
            payload.put("parentId", event.parentId());
            payload.put("runId", event.runId());
            payload.put("agentScope", event.agentScope());
            payload.put("agentId", event.agentId());
            payload.put("agentLabel", event.agentLabel());
            payload.put("kind", event.kind());
            payload.put("name", event.name());
            payload.put("title", event.title());
            payload.put("op", event.op());
            payload.put("status", event.status());
            payload.put("timestamp", event.timestamp());
            payload.put("meta", event.meta() != null ? event.meta() : Map.of());
            traceContext.sink().send("run_step", payload);
            log.info("[SseTracePublisher] SSE sent: type=run_step, runId={}, id={}, parentId={}, kind={}, status={}, title={}, op={}, agentId={}, agentScope={}",
                    event.runId(), event.id(), event.parentId(), event.kind(), event.status(), event.title(),
                    event.op(), event.agentId(), event.agentScope());
        } catch (Exception e) {
            log.warn("[SseTracePublisher] SSE send failed: type=run_step, runId={}, id={}, parentId={}, kind={}, status={}, title={}, error={}",
                    event.runId(), event.id(), event.parentId(), event.kind(), event.status(), event.title(), e.getMessage());
        }
    }
}
