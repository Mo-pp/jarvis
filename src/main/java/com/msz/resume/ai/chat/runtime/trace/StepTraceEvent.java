package com.msz.resume.ai.chat.runtime.trace;

import lombok.Builder;

import java.time.Instant;
import java.util.Map;

/**
 * 统一的运行步骤事件。
 *
 * 作用：描述 Trace 树里某一个节点在某个时刻发生了什么，
 * 比如某轮 LLM 开始了、某个工具失败了、某个子 Agent 完成了。
 * 它相当于前后端之间共享的“步骤事件标准件”。
 */
@Builder
public record StepTraceEvent(
        String id,
        String parentId,
        String runId,
        String agentScope,
        String agentId,
        String agentLabel,
        String kind,
        String name,
        String title,
        String op,
        String status,
        Instant timestamp,
        Map<String, Object> meta
) {
}
