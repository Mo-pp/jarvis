package com.msz.resume.ai.chat.runtime.trace;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当前活跃 SSE 连接与 Trace 运行的全局上下文。
 *
 * 作用：把 sessionId、runId、SSE Sink、Trace 上下文串起来，
 * 让状态机里任何一个节点都能顺着 sessionId / runId 找到当前还能不能往前端发流式消息。
 * 可以把它理解成“直播间路由表”。
 *
 * 代码逻辑：
 * 1. 维护 sessionId -> sink 的映射
 * 2. 维护 sessionId / runId -> traceContext 的映射
 * 3. 提供绑定、清理、活跃性判断和按会话发增量文本的静态入口
 */
public final class ChatStreamContext {

    private static final Map<String, ChatStreamEventSink> SINKS = new ConcurrentHashMap<>();
    private static final Map<String, ChatRunTraceContext> RUN_CONTEXTS = new ConcurrentHashMap<>();
    private static final Map<String, ChatRunTraceContext> RUN_ID_CONTEXTS = new ConcurrentHashMap<>();

    /** 私有构造，明确这是一个只负责静态路由的工具类。 */
    private ChatStreamContext() {
    }

    /** 绑定会话和 SSE Sink，让后续代码能按 sessionId 找到这条连接。 */
    public static void bind(String sessionId, ChatStreamEventSink sink) {
        if (sessionId != null && sink != null) {
            SINKS.put(sessionId, sink);
        }
    }

    /** 绑定运行中的 Trace 上下文，顺便把它的 sink 也登记进去。 */
    public static void bindRun(String sessionId, ChatRunTraceContext traceContext) {
        if (sessionId != null && traceContext != null) {
            RUN_CONTEXTS.put(sessionId, traceContext);
            RUN_ID_CONTEXTS.put(traceContext.runId(), traceContext);
            bind(sessionId, traceContext.sink());
        }
    }

    /** 清除会话的 SSE 和 Trace 绑定，像对一场已经结束的直播做收尾。 */
    public static void clear(String sessionId) {
        if (sessionId != null) {
            SINKS.remove(sessionId);
            ChatRunTraceContext removed = RUN_CONTEXTS.remove(sessionId);
            if (removed != null) {
                RUN_ID_CONTEXTS.remove(removed.runId());
            }
        }
    }

    /** 检查某个会话的 SSE 连接是否还活着。 */
    public static boolean isActive(String sessionId) {
        ChatStreamEventSink sink = SINKS.get(sessionId);
        return sink != null && !sink.isClosed();
    }

    /** 按 sessionId 取当前 Trace 上下文，给主链节点直接回查。 */
    public static ChatRunTraceContext getTraceContext(String sessionId) {
        return RUN_CONTEXTS.get(sessionId);
    }

    /** 先按 sessionId 查，再按 runId 兜底查，支持子 Agent 继续把事件透传回父流。 */
    public static ChatRunTraceContext getTraceContext(String sessionId, String runId) {
        ChatRunTraceContext context = sessionId != null ? RUN_CONTEXTS.get(sessionId) : null;
        if (context != null) {
            return context;
        }
        return runId != null ? RUN_ID_CONTEXTS.get(runId) : null;
    }

    /** 按会话发送增量文本，让前端打字机效果一段一段往外冒。 */
    public static void sendDelta(String sessionId, String delta) throws IOException {
        ChatStreamEventSink sink = SINKS.get(sessionId);
        if (sink == null || sink.isClosed() || delta == null || delta.isEmpty()) {
            return;
        }
        sink.send("message_delta", Map.of(
                "role", "assistant",
                "delta", delta
        ));
    }
}
