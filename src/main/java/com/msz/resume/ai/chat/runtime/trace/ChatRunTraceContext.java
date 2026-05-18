package com.msz.resume.ai.chat.runtime.trace;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次对话流式运行的 Trace 上下文。
 *
 * 作用：保存一次 SSE / Trace 运行里最核心的现场信息，比如 runId、sessionId、事件出口、
 * 步骤编号、Agent 根节点、工具节点父子关系等。
 * 可以把它理解成这次执行的“现场指挥板”，后面的 TraceService、SSE 发布器都会来这里取号和查关系。
 *
 * 代码逻辑：
 * 1. 保存本轮运行的基础标识和输出通道
 * 2. 通过自增序号生成 stepId 和 subAgent 序号
 * 3. 维护工具步骤、父子步骤、Agent 根步骤三类索引
 * 4. 对外提供活跃状态判断，避免连接断了还继续发事件
 */
public class ChatRunTraceContext {

    private final String runId;
    private final String sessionId;
    private final ChatStreamEventSink sink;
    private final TracePublisher publisher;
    private final AtomicLong stepSequence = new AtomicLong(0);
    private final AtomicLong subAgentSequence = new AtomicLong(0);
    private final Map<String, String> toolCallIdToStepId = new ConcurrentHashMap<>();
    private final Map<String, String> stepParentIndex = new ConcurrentHashMap<>();
    private final Map<String, String> agentIdToRootStepId = new ConcurrentHashMap<>();
    private volatile String mainLlmStepId;

    /** 创建基于 SSE Sink 的 Trace 上下文，用于真实对话流里往前端推事件。 */
    public ChatRunTraceContext(String runId, String sessionId, ChatStreamEventSink sink) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.sink = sink;
        this.publisher = null;
    }

    /** 创建基于自定义发布器的 Trace 上下文，主要给测试或非 SSE 场景复用。 */
    ChatRunTraceContext(String runId, String sessionId, TracePublisher publisher) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.sink = null;
        this.publisher = publisher;
    }

    /** 返回本次运行的 runId，相当于给整条执行链贴的工单号。 */
    public String runId() {
        return runId;
    }

    /** 返回当前 Trace 绑定的 sessionId，用来把事件挂回具体会话。 */
    public String sessionId() {
        return sessionId;
    }

    /** 返回当前运行使用的 SSE 事件出口。 */
    public ChatStreamEventSink sink() {
        return sink;
    }

    /** 返回底层步骤发布器，优先给测试或替代发布方案使用。 */
    TracePublisher publisher() {
        return publisher;
    }

    /** 判断当前 Trace 是否还能继续发事件，像看“直播线路”是不是还通着。 */
    public boolean isActive() {
        return publisher != null || (sink != null && !sink.isClosed());
    }

    /** 生成下一个步骤 ID，让每个 Trace 节点都有唯一编号。 */
    public String nextStepId() {
        return "step_" + stepSequence.incrementAndGet();
    }

    /** 返回主 Agent 当前那一轮 LLM 节点的 stepId。 */
    public String mainLlmStepId() {
        return mainLlmStepId;
    }

    /** 记录主 Agent 的 LLM 根步骤，后续更新完成/失败状态时要回头找到它。 */
    public void rememberMainLlmStep(String stepId) {
        if (stepId != null && !stepId.isBlank()) {
            this.mainLlmStepId = stepId;
        }
    }

    /** 记录某个 Agent 的根步骤，方便后续把工具批次、召回步骤都挂到它下面。 */
    public void rememberAgentRootStep(String agentId, String stepId) {
        if (agentId != null && !agentId.isBlank() && stepId != null && !stepId.isBlank()) {
            agentIdToRootStepId.put(agentId, stepId);
        }
    }

    /** 按 Agent ID 查它的根步骤，相当于给这个 Agent 找到它的树根。 */
    public String findAgentRootStep(String agentId) {
        return agentId != null ? agentIdToRootStepId.get(agentId) : null;
    }

    /** 生成下一个子 Agent 序号，用来拼出 `sub_1`、`sub_2` 这种展示编号。 */
    public long nextSubAgentSequence() {
        return subAgentSequence.incrementAndGet();
    }

    /** 记录工具调用对应的步骤 ID，后面更新 started/completed 时就能对上号。 */
    public void rememberToolStep(String toolCallId, String stepId) {
        if (toolCallId != null && !toolCallId.isBlank() && stepId != null && !stepId.isBlank()) {
            toolCallIdToStepId.put(toolCallId, stepId);
        }
    }

    /** 根据工具调用 ID 查步骤 ID，像查“这把工具调用目前挂在哪个节点上”。 */
    public String findToolStep(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return null;
        }
        return toolCallIdToStepId.get(toolCallId);
    }

    /** 记录某个步骤的父节点，方便前端还原出树形 Trace。 */
    public void rememberStepParent(String stepId, String parentId) {
        if (stepId != null && !stepId.isBlank() && parentId != null && !parentId.isBlank()) {
            stepParentIndex.put(stepId, parentId);
        }
    }

    /** 查询某个步骤的父节点，像顺着树枝往上找上一级。 */
    public String findStepParent(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return null;
        }
        return stepParentIndex.get(stepId);
    }
}
