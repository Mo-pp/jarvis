package com.msz.resume.ai.chat.runtime.trace;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trace 步骤树管理服务。
 *
 * 作用：统一创建和更新一次运行里的步骤树节点，
 * 包括主 LLM 轮次、工具批次、单个工具调用、上下文召回、子 Agent 等。
 * 可以把它理解成“Trace 总调度器”，负责给所有节点发号、挂父子关系、推状态变化。
 *
 * 代码逻辑：
 * 1. 为主 Agent / 子 Agent 建立根步骤
 * 2. 为工具批次和工具调用创建树节点并维护索引
 * 3. 在 started / completed / failed / pending / blocked 之间切换节点状态
 * 4. 通过 TracePublisher 把标准化 StepTraceEvent 发出去
 */
@Service
public class TraceService {

    /** 启动一轮 LLM Trace 节点，给当前 Agent 挂上新的根步骤。 */
    public void startLlmRound(ChatRunTraceContext context, TraceAgentDescriptor agentDescriptor) {
        String stepId = context.nextStepId();
        context.rememberAgentRootStep(agentDescriptor.agentId(), stepId);
        if ("main".equals(agentDescriptor.agentScope())) {
            context.rememberMainLlmStep(stepId);
        }
        publish(context, agentDescriptor, StepTraceEvent.builder()
                .id(stepId)
                .runId(context.runId())
                .agentScope(agentDescriptor.agentScope())
                .agentId(agentDescriptor.agentId())
                .agentLabel(agentDescriptor.agentLabel())
                .kind("llm")
                .name("llm")
                .title(agentDescriptor.agentLabel())
                .op("started")
                .status("running")
                .timestamp(Instant.now())
                .meta(Map.of())
                .build());
    }

    /** 把主 Agent 当前那轮 LLM 节点标记为成功完成。 */
    public void completeMainLlmRound(ChatRunTraceContext context) {
        publishMainLlmRoundState(context, "completed", "success");
    }

    /** 把主 Agent 当前那轮 LLM 节点标记为失败结束。 */
    public void failMainLlmRound(ChatRunTraceContext context) {
        publishMainLlmRoundState(context, "failed", "failed");
    }

    /** 记录 OpenViking 自动召回这一步的状态，让前端能看到是否触发、注入、失败或跳过。 */
    public void recordContextRecall(ChatRunTraceContext context,
                                    TraceAgentDescriptor agentDescriptor,
                                    String op,
                                    String status,
                                    Map<String, Object> meta) {
        if (context == null || agentDescriptor == null) {
            return;
        }
        String stepId = context.nextStepId();
        String parentId = context.findAgentRootStep(agentDescriptor.agentId());
        publish(context, agentDescriptor, StepTraceEvent.builder()
                .id(stepId)
                .parentId(parentId)
                .runId(context.runId())
                .agentScope(agentDescriptor.agentScope())
                .agentId(agentDescriptor.agentId())
                .agentLabel(agentDescriptor.agentLabel())
                .kind("context_recall")
                .name("openviking_recall")
                .title("OpenViking 自动召回")
                .op(op)
                .status(status)
                .timestamp(Instant.now())
                .meta(meta != null ? meta : Map.of())
                .build());
    }

    /** 统一更新主 LLM 节点的终态，像给整轮主思考过程补上结案状态。 */
    private void publishMainLlmRoundState(ChatRunTraceContext context, String op, String status) {
        if (context == null || context.mainLlmStepId() == null) {
            return;
        }
        TraceAgentDescriptor mainAgent = TraceAgentDescriptor.mainAgent();
        publish(context, mainAgent, StepTraceEvent.builder()
                .id(context.mainLlmStepId())
                .runId(context.runId())
                .agentScope(mainAgent.agentScope())
                .agentId(mainAgent.agentId())
                .agentLabel(mainAgent.agentLabel())
                .kind("llm")
                .name("llm")
                .title(mainAgent.agentLabel())
                .op(op)
                .status(status)
                .timestamp(Instant.now())
                .meta(Map.of())
                .build());
    }

    /** 创建一个工具批次根节点，把这一轮并发工具调用收进同一组里。 */
    public String startToolBatch(ChatRunTraceContext context,
                                 TraceAgentDescriptor agentDescriptor,
                                 List<ToolExecutionRequest> requests) {
        String stepId = context.nextStepId();
        String parentId = context.findAgentRootStep(agentDescriptor.agentId());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("count", requests != null ? requests.size() : 0);
        publish(context, agentDescriptor, StepTraceEvent.builder()
                .id(stepId)
                .parentId(parentId)
                .runId(context.runId())
                .agentScope(agentDescriptor.agentScope())
                .agentId(agentDescriptor.agentId())
                .agentLabel(agentDescriptor.agentLabel())
                .kind("tool_batch")
                .name("tool_batch")
                .title("批量工具调用")
                .op("started")
                .status("running")
                .timestamp(Instant.now())
                .meta(meta)
                .build());
        return stepId;
    }

    /** 确保一批工具调用一定挂在某个批次节点下，避免重复创建批次。 */
    public String ensureToolBatch(ChatRunTraceContext context,
                                  TraceAgentDescriptor agentDescriptor,
                                  List<ToolExecutionRequest> requests) {
        if (context == null) {
            return null;
        }

        String batchStepId = findExistingToolBatch(context, requests);
        if (batchStepId == null || batchStepId.isBlank()) {
            batchStepId = startToolBatch(context, agentDescriptor, requests);
        }

        if (requests != null) {
            for (ToolExecutionRequest request : requests) {
                if (request == null) {
                    continue;
                }
                String toolStepId = context.findToolStep(request.id());
                if (toolStepId == null || toolStepId.isBlank()) {
                    planToolCall(context, agentDescriptor, batchStepId, request);
                }
            }
        }

        return batchStepId;
    }

    /** 根据已有工具调用反查它们所在的批次节点，像从子节点往上找它的那一组。 */
    private String findExistingToolBatch(ChatRunTraceContext context, List<ToolExecutionRequest> requests) {
        if (context == null || requests == null) {
            return null;
        }
        for (ToolExecutionRequest request : requests) {
            if (request == null) {
                continue;
            }
            String toolStepId = context.findToolStep(toolCallKey(request));
            if (toolStepId == null || toolStepId.isBlank()) {
                continue;
            }
            String parentId = context.findStepParent(toolStepId);
            if (parentId != null && !parentId.isBlank()) {
                return parentId;
            }
        }
        return null;
    }

    /** 把工具批次标记为成功完成。 */
    public void completeToolBatch(ChatRunTraceContext context,
                                  TraceAgentDescriptor agentDescriptor,
                                  String batchStepId) {
        publishToolBatchState(context, agentDescriptor, batchStepId, "completed", "success");
    }

    /** 把工具批次标记为失败。 */
    public void failToolBatch(ChatRunTraceContext context,
                              TraceAgentDescriptor agentDescriptor,
                              String batchStepId) {
        publishToolBatchState(context, agentDescriptor, batchStepId, "failed", "failed");
    }

    /** 把工具批次标记为等待用户或下游条件继续推进。 */
    public void pendingToolBatch(ChatRunTraceContext context,
                                 TraceAgentDescriptor agentDescriptor,
                                 String batchStepId) {
        publishToolBatchState(context, agentDescriptor, batchStepId, "pending", "pending");
    }

    /** 统一发布工具批次状态变化。 */
    private void publishToolBatchState(ChatRunTraceContext context,
                                       TraceAgentDescriptor agentDescriptor,
                                       String batchStepId,
                                       String op,
                                       String status) {
        if (context == null || batchStepId == null || batchStepId.isBlank()) {
            return;
        }
        publish(context, agentDescriptor, StepTraceEvent.builder()
                .id(batchStepId)
                .parentId(context.findAgentRootStep(agentDescriptor.agentId()))
                .runId(context.runId())
                .agentScope(agentDescriptor.agentScope())
                .agentId(agentDescriptor.agentId())
                .agentLabel(agentDescriptor.agentLabel())
                .kind("tool_batch")
                .name("tool_batch")
                .title("批量工具调用")
                .op(op)
                .status(status)
                .timestamp(Instant.now())
                .meta(Map.of())
                .build());
    }

    /** 为单个工具调用预先创建一个 Trace 节点，让前端先看到它已进入待执行队列。 */
    public String planToolCall(ChatRunTraceContext context,
                               TraceAgentDescriptor agentDescriptor,
                               String batchStepId,
                               ToolExecutionRequest request) {
        String stepId = context.nextStepId();
        String toolCallId = toolCallKey(request);
        context.rememberToolStep(toolCallId, stepId);
        context.rememberStepParent(stepId, batchStepId);
        publish(context, agentDescriptor, StepTraceEvent.builder()
                .id(stepId)
                .parentId(batchStepId)
                .runId(context.runId())
                .agentScope(agentDescriptor.agentScope())
                .agentId(agentDescriptor.agentId())
                .agentLabel(agentDescriptor.agentLabel())
                .kind("tool_call")
                .name(request.name())
                .title(request.name())
                .op("created")
                .status("running")
                .timestamp(Instant.now())
                .meta(buildToolMeta(request))
                .build());
        return stepId;
    }

    /** 把单个工具节点标记为开始执行。 */
    public void startToolCall(ChatRunTraceContext context,
                              TraceAgentDescriptor agentDescriptor,
                              ToolExecutionRequest request) {
        publishToolState(context, agentDescriptor, request, "started", "running");
    }

    /** 把单个工具节点标记为成功完成。 */
    public void completeToolCall(ChatRunTraceContext context,
                                 TraceAgentDescriptor agentDescriptor,
                                 ToolExecutionRequest request) {
        publishToolState(context, agentDescriptor, request, "completed", "success");
    }

    /** 把单个工具节点标记为失败。 */
    public void failToolCall(ChatRunTraceContext context,
                             TraceAgentDescriptor agentDescriptor,
                             ToolExecutionRequest request) {
        publishToolState(context, agentDescriptor, request, "failed", "failed");
    }

    /** 把单个工具节点标记为被阻断，常用于 Hook 安全拦截。 */
    public void blockToolCall(ChatRunTraceContext context,
                              TraceAgentDescriptor agentDescriptor,
                              ToolExecutionRequest request) {
        publishToolState(context, agentDescriptor, request, "blocked", "blocked");
    }

    /** 把单个工具节点标记为 pending，常用于 AskUserQuestion 这类等待用户输入的情况。 */
    public void pendingToolCall(ChatRunTraceContext context,
                                TraceAgentDescriptor agentDescriptor,
                                ToolExecutionRequest request) {
        publishToolState(context, agentDescriptor, request, "pending", "pending");
    }

    /** 创建一个新的子 Agent 描述对象，像给新分出去的执行小组发工牌。 */
    public TraceAgentDescriptor createSubAgentDescriptor(ChatRunTraceContext context, String subAgentType) {
        long sequence = context.nextSubAgentSequence();
        String typeLabel = (subAgentType != null && !subAgentType.isBlank()) ? subAgentType : "General";
        return new TraceAgentDescriptor(
                "sub_" + sequence,
                "sub",
                typeLabel + " #" + sequence,
                subAgentType
        );
    }

    /** 创建子 Agent 根节点，并把它挂到触发它的工具调用下面。 */
    public String startSubAgent(ChatRunTraceContext context,
                                TraceAgentDescriptor parentAgent,
                                ToolExecutionRequest request,
                                TraceAgentDescriptor subAgentDescriptor) {
        String stepId = context.nextStepId();
        String parentId = context.findToolStep(toolCallKey(request));
        context.rememberAgentRootStep(subAgentDescriptor.agentId(), stepId);
        context.rememberStepParent(stepId, parentId);
        publish(context, subAgentDescriptor, StepTraceEvent.builder()
                .id(stepId)
                .parentId(parentId)
                .runId(context.runId())
                .agentScope(subAgentDescriptor.agentScope())
                .agentId(subAgentDescriptor.agentId())
                .agentLabel(subAgentDescriptor.agentLabel())
                .kind("sub_agent")
                .name("spawnAgent")
                .title(subAgentDescriptor.agentLabel())
                .op("started")
                .status("running")
                .timestamp(Instant.now())
                .meta(Map.of(
                        "toolCallId", request.id(),
                        "subAgentType", subAgentDescriptor.subAgentType() != null ? subAgentDescriptor.subAgentType() : ""
                ))
                .build());
        return stepId;
    }

    /** 把子 Agent 节点标记为成功完成。 */
    public void completeSubAgent(ChatRunTraceContext context, TraceAgentDescriptor subAgentDescriptor, String subAgentStepId) {
        publish(context, subAgentDescriptor, StepTraceEvent.builder()
                .id(subAgentStepId)
                .parentId(context.findStepParent(subAgentStepId))
                .runId(context.runId())
                .agentScope(subAgentDescriptor.agentScope())
                .agentId(subAgentDescriptor.agentId())
                .agentLabel(subAgentDescriptor.agentLabel())
                .kind("sub_agent")
                .name("spawnAgent")
                .title(subAgentDescriptor.agentLabel())
                .op("completed")
                .status("success")
                .timestamp(Instant.now())
                .meta(Map.of("subAgentType", subAgentDescriptor.subAgentType() != null ? subAgentDescriptor.subAgentType() : ""))
                .build());
    }

    /** 把子 Agent 节点标记为失败。 */
    public void failSubAgent(ChatRunTraceContext context, TraceAgentDescriptor subAgentDescriptor, String subAgentStepId) {
        publish(context, subAgentDescriptor, StepTraceEvent.builder()
                .id(subAgentStepId)
                .parentId(context.findStepParent(subAgentStepId))
                .runId(context.runId())
                .agentScope(subAgentDescriptor.agentScope())
                .agentId(subAgentDescriptor.agentId())
                .agentLabel(subAgentDescriptor.agentLabel())
                .kind("sub_agent")
                .name("spawnAgent")
                .title(subAgentDescriptor.agentLabel())
                .op("failed")
                .status("failed")
                .timestamp(Instant.now())
                .meta(Map.of("subAgentType", subAgentDescriptor.subAgentType() != null ? subAgentDescriptor.subAgentType() : ""))
                .build());
    }

    /** 统一更新单个工具节点状态；如果之前没建过节点，这里会顺手补一个。 */
    private void publishToolState(ChatRunTraceContext context,
                                  TraceAgentDescriptor agentDescriptor,
                                  ToolExecutionRequest request,
                                  String op,
                                  String status) {
        String toolCallId = toolCallKey(request);
        String stepId = context.findToolStep(toolCallId);
        if (stepId == null || stepId.isBlank()) {
            stepId = context.nextStepId();
            context.rememberToolStep(toolCallId, stepId);
        }

        publish(context, agentDescriptor, StepTraceEvent.builder()
                .id(stepId)
                .parentId(context.findStepParent(stepId))
                .runId(context.runId())
                .agentScope(agentDescriptor.agentScope())
                .agentId(agentDescriptor.agentId())
                .agentLabel(agentDescriptor.agentLabel())
                .kind("tool_call")
                .name(request.name())
                .title(request.name())
                .op(op)
                .status(status)
                .timestamp(Instant.now())
                .meta(buildToolMeta(request))
                .build());
    }

    /** 构造工具节点的最小元信息，目前主要记录 toolCallId。 */
    private Map<String, Object> buildToolMeta(ToolExecutionRequest request) {
        return Map.of(
                "toolCallId", request.id() != null ? request.id() : ""
        );
    }

    /** 为工具调用生成稳定主键，优先用 toolCallId，没有时再退回对象身份。 */
    private String toolCallKey(ToolExecutionRequest request) {
        if (request != null && request.id() != null && !request.id().isBlank()) {
            return request.id();
        }
        return request != null
                ? request.name() + "_" + Integer.toHexString(System.identityHashCode(request))
                : "unknown_tool";
    }

    /** 统一发布步骤事件，优先走上下文里自带发布器，否则退回 SSE 发布器。 */
    private void publish(ChatRunTraceContext context, TraceAgentDescriptor agentDescriptor, StepTraceEvent event) {
        if (context == null || event == null) {
            return;
        }
        TracePublisher publisher = context.publisher() != null
                ? context.publisher()
                : new SseTracePublisher(context);
        publisher.publishStep(event);
    }
}
