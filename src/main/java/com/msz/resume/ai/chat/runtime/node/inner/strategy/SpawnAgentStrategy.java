package com.msz.resume.ai.chat.runtime.node.inner.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msz.resume.ai.agent.SubAgentType;
import com.msz.resume.ai.chat.runtime.trace.DelegationActionEventService;
import com.msz.resume.ai.chat.runtime.trace.TraceAgentDescriptor;
import com.msz.resume.ai.chat.runtime.trace.TraceService;
import com.msz.resume.ai.chat.prompt.model.UserProfile;
import com.msz.resume.ai.chat.runtime.state.QueryLoopState;
import com.msz.resume.ai.chat.runtime.subagent.SubAgentResult;
import com.msz.resume.ai.chat.runtime.subagent.SubGraphNode;
import com.msz.resume.ai.tool.ToolRuntimeContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 子 Agent 派发策略。
 *
 * 作用：专门处理 spawnAgent 工具调用，把子任务分发到子图执行，
 * 同时继续兼容同批次里混进来的普通工具，并把派发过程完整记进 trace。
 * 可以把它理解成“外包调度员”，主 Agent 把子任务分出去，它负责盯执行、收结果、回主链。
 *
 * 代码逻辑：
 * 1. 先把同一批请求拆成 spawnAgent 请求和普通工具请求
 * 2. 普通工具仍复用 NormalToolStrategy 走原有链路
 * 3. spawnAgent 请求解析参数后并行执行子图，并为每个子 Agent 建独立 trace 节点
 * 4. 等全部子任务收齐后，汇总结果、trace 状态和 token 用量返回给主流程
 */
@Slf4j
@Component
public class SpawnAgentStrategy implements ToolExecutionStrategy {

    private static final String TOOL_NAME = "spawnAgent";

    private final NormalToolStrategy normalToolStrategy;
    private final SubGraphNode subGraphNode;
    private final TraceService traceService;
    private final DelegationActionEventService delegationActionEventService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 注入普通工具策略、子图执行器和子 Agent 相关 trace 事件服务。 */
    public SpawnAgentStrategy(NormalToolStrategy normalToolStrategy,
                              SubGraphNode subGraphNode,
                              TraceService traceService,
                              DelegationActionEventService delegationActionEventService) {
        this.normalToolStrategy = normalToolStrategy;
        this.subGraphNode = subGraphNode;
        this.traceService = traceService;
        this.delegationActionEventService = delegationActionEventService;
    }

    @Override
    /** 只接管 spawnAgent 工具调用。 */
    public boolean supports(ToolExecutionRequest request) {
        return TOOL_NAME.equals(request.name());
    }

    @Override
    /** 给予较高优先级，保证 spawnAgent 不会被普通工具策略抢走。 */
    public int getPriority() {
        // 高优先级，优先于普通工具策略
        return 10;
    }

    @Override
    /** 协调同一批次里的子 Agent 派发和普通工具执行，并汇总成统一结果。 */
    public ToolExecutionResult execute(ToolExecutionContext context) {
        QueryLoopState state = context.state();
        List<ToolExecutionRequest> requests = context.requests();

        // 分离 spawnAgent 请求和普通工具请求
        List<ToolExecutionRequest> spawnAgentRequests = new ArrayList<>();
        List<ToolExecutionRequest> normalRequests = new ArrayList<>();

        for (ToolExecutionRequest req : requests) {
            if (TOOL_NAME.equals(req.name())) {
                spawnAgentRequests.add(req);
            } else {
                normalRequests.add(req);
            }
        }

        log.info("[SpawnAgentStrategy] 处理 {} 个 spawnAgent 请求, {} 个普通工具请求",
                spawnAgentRequests.size(), normalRequests.size());

        List<ToolExecutionResultMessage> allResults = new ArrayList<>(context.blockedResults());
        List<ToolExecutionRequest> allContexts = new ArrayList<>(context.blockedRequests());
        Set<String> discoveredTools = new LinkedHashSet<>(state.getDiscoveredTools());
        String finalTransition = ToolExecutionResult.TRANSITION_SUCCESS;
        List<Map<String, Object>> taskPlan = null;

        // 1. 执行普通工具请求（如果有）
        if (!normalRequests.isEmpty()) {
            ToolExecutionResult normalResult = executeNormalTools(context, normalRequests);
            allResults.addAll(normalResult.messages());
            allContexts.addAll(normalResult.contexts());
            if (ToolExecutionResult.TRANSITION_FAILED.equals(normalResult.transition())) {
                finalTransition = ToolExecutionResult.TRANSITION_FAILED;
            } else if (ToolExecutionResult.TRANSITION_ARTIFACT_READY.equals(normalResult.transition())) {
                finalTransition = ToolExecutionResult.TRANSITION_ARTIFACT_READY;
            }
            discoveredTools = normalResult.discoveredTools();
            taskPlan = normalResult.taskPlan();
        }

        // 2. 并行执行所有 spawnAgent 请求
        UserProfile userContext = state.getUserContext();
        String sessionId = state.getSessionId();
        ToolRuntimeContext.setSessionId(sessionId);
        ToolRuntimeContext.setOpenVikingIdentity(context.openVikingIdentity());
        ToolRuntimeContext.setRunId(state.getTraceRunId());
        ToolRuntimeContext.setAgentId(state.getTraceAgentId());
        ToolRuntimeContext.setAgentLabel(state.getTraceAgentLabel());

        List<CompletableFuture<SpawnAgentExecutionResult>> spawnAgentFutures = new ArrayList<>();
        try {
            for (ToolExecutionRequest req : spawnAgentRequests) {
                if (context.traceContext() != null) {
                    traceService.startToolCall(context.traceContext(), context.agentDescriptor(), req);
                }
                SpawnAgentParams params = parseSpawnAgentParams(req);
                if (params == null) {
                    allResults.add(ToolExecutionResultMessage.from(req,
                            "spawnAgent 参数解析失败：无法解析 prompt、allowedTools、maxTurns"));
                    allContexts.add(req);
                    finalTransition = ToolExecutionResult.TRANSITION_FAILED;
                    if (context.traceContext() != null) {
                        traceService.failToolCall(context.traceContext(), context.agentDescriptor(), req);
                    }
                    continue;
                }
                TraceAgentDescriptor subAgentDescriptor = context.traceContext() != null
                        ? traceService.createSubAgentDescriptor(context.traceContext(), params.subagentType().name())
                        : new TraceAgentDescriptor("sub", "sub", params.subagentType().name(), params.subagentType().name());
                String subAgentStepId = context.traceContext() != null
                        ? traceService.startSubAgent(context.traceContext(), context.agentDescriptor(), req, subAgentDescriptor)
                        : null;
                delegationActionEventService.delegationStarted(context.traceContext(), subAgentDescriptor, req, params.prompt());
                spawnAgentFutures.add(executeSpawnAgent(req, params, sessionId, userContext, state, subAgentDescriptor, subAgentStepId));
            }
        } finally {
            ToolRuntimeContext.clear();
        }

        // 等待所有派发任务完成，并收集 token 数据
        List<Map<String, Integer>> subAgentTokenData = new ArrayList<>();

        for (CompletableFuture<SpawnAgentExecutionResult> future : spawnAgentFutures) {
            try {
                SpawnAgentExecutionResult saResult = future.get();
                allResults.add(saResult.resultMessage());
                allContexts.add(saResult.request());
                // spawnAgent 工具调用本身成功（子Agent启动、执行、返回了结果），
                // 只有子Agent异常才视为工具执行失败；超时是正常终态，不算失败。
                if (saResult.subAgentResult() != null && saResult.subAgentResult().isError()) {
                    finalTransition = ToolExecutionResult.TRANSITION_FAILED;
                    delegationActionEventService.delegationFailed(
                            context.traceContext(),
                            saResult.subAgentDescriptor(),
                            saResult.request(),
                            saResult.taskDescription(),
                            saResult.subAgentResult().summary()
                    );
                    if (context.traceContext() != null && saResult.subAgentStepId() != null) {
                        traceService.failSubAgent(context.traceContext(), saResult.subAgentDescriptor(), saResult.subAgentStepId());
                    }
                } else {
                    delegationActionEventService.delegationSucceeded(
                            context.traceContext(),
                            saResult.subAgentDescriptor(),
                            saResult.request(),
                            saResult.taskDescription(),
                            saResult.subAgentResult()
                    );
                }
                // 累积子Agent的token用量
                if (saResult.subAgentResult() != null) {
                    subAgentTokenData.add(Map.of(
                            "inputTokens", saResult.subAgentResult().inputTokens(),
                            "outputTokens", saResult.subAgentResult().outputTokens()
                    ));
                    log.info("[SpawnAgentStrategy] 子Agent token: input={}, output={}",
                            saResult.subAgentResult().inputTokens(),
                            saResult.subAgentResult().outputTokens());
                }
                if (context.traceContext() != null) {
                    if (saResult.subAgentResult() != null && !saResult.subAgentResult().isError() && saResult.subAgentStepId() != null) {
                        traceService.completeSubAgent(context.traceContext(), saResult.subAgentDescriptor(), saResult.subAgentStepId());
                    }
                    if (saResult.success()) {
                        traceService.completeToolCall(context.traceContext(), context.agentDescriptor(), saResult.request());
                    } else {
                        traceService.failToolCall(context.traceContext(), context.agentDescriptor(), saResult.request());
                    }
                }
            } catch (Exception e) {
                log.error("[SpawnAgentStrategy] 等待子Agent结果异常", e);
                // 单个子Agent失败不影响其他子Agent
                finalTransition = ToolExecutionResult.TRANSITION_FAILED;
            }
        }

        return ToolExecutionResult.builder()
                .messages(allResults)
                .contexts(allContexts)
                .transition(finalTransition)
                .discoveredTools(discoveredTools)
                .taskPlan(taskPlan)
                .build();
    }

    /**
     * 执行同一批次里的普通工具请求。
     *
     * <p>复用 NormalToolStrategy，确保混合 spawnAgent 批次和纯普通工具批次拥有同一套
     * 用户可见 action 事件、artifact_ready、task checkpoint 与结果摘要逻辑。
     */
    private ToolExecutionResult executeNormalTools(
            ToolExecutionContext context,
            List<ToolExecutionRequest> requests) {

        ToolExecutionContext normalContext = ToolExecutionContext.builder()
                .state(context.state())
                .openVikingIdentity(context.openVikingIdentity())
                .traceContext(context.traceContext())
                .agentDescriptor(context.agentDescriptor())
                .requests(requests)
                .blockedResults(List.of())
                .blockedRequests(List.of())
                .build();

        return normalToolStrategy.execute(normalContext);
    }

    /**
     * 解析 spawnAgent 参数
     */
    private SpawnAgentParams parseSpawnAgentParams(ToolExecutionRequest request) {
        try {
            JsonNode args = objectMapper.readTree(request.arguments());

            // 支持新参数名 prompt 和旧参数名 taskDescription（向后兼容）
            String prompt = args.has("prompt") ? args.get("prompt").asText() : null;
            if (prompt == null || prompt.isBlank()) {
                prompt = args.has("taskDescription") ? args.get("taskDescription").asText() : null;
            }
            if (prompt == null || prompt.isBlank()) {
                log.warn("[SpawnAgentStrategy] spawnAgent 缺少 prompt 参数");
                return null;
            }

            // 解析 subagentType 参数（支持新旧参数名）
            String subagentTypeStr = args.has("subagentType") ? args.get("subagentType").asText() : null;
            if (subagentTypeStr == null) {
                subagentTypeStr = args.has("agentType") ? args.get("agentType").asText() : "General";
            }
            SubAgentType subagentType;
            try {
                subagentType = SubAgentType.valueOf(subagentTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("[SpawnAgentStrategy] 无效的 subagentType: {}, 使用默认值 General", subagentTypeStr);
                subagentType = SubAgentType.General;
            }

            // 解析 allowedTools 参数（仅 General 类型使用）
            Set<String> permittedTools = new LinkedHashSet<>();
            if (subagentType.supportsCustomTools()) {
                // 支持新参数名 allowedTools（优先）和旧参数名 toolNames
                String allowedToolsStr = args.has("allowedTools") ? args.get("allowedTools").asText() : null;
                if (allowedToolsStr == null || allowedToolsStr.isBlank()) {
                    allowedToolsStr = args.has("toolNames") ? args.get("toolNames").asText() : "";
                }
                if (allowedToolsStr != null && !allowedToolsStr.isBlank()) {
                    for (String name : allowedToolsStr.split(",")) {
                        String trimmed = name.trim();
                        if (!trimmed.isEmpty()) {
                            permittedTools.add(trimmed);
                        }
                    }
                }
            }
            // Plan/Explore 类型：permittedTools 保持空，由 SubAgentTypeRegistry 解析

            int maxTurns = args.has("maxTurns") ? args.get("maxTurns").asInt() : 30;
            if (maxTurns <= 0) {
                maxTurns = 30; // 默认值
            }

            log.info("[SpawnAgentStrategy] spawnAgent 参数: prompt={}, subagentType={}, tools={}, maxTurns={}",
                    prompt, subagentType,
                    subagentType.supportsCustomTools() ? permittedTools : "(预定义)",
                    maxTurns);

            return new SpawnAgentParams(prompt, subagentType, permittedTools, maxTurns);
        } catch (Exception e) {
            log.error("[SpawnAgentStrategy] 解析 spawnAgent 参数失败", e);
            return null;
        }
    }

    /** 启动单个子 Agent 子图，并把最终结果包装回工具返回消息。 */
    private CompletableFuture<SpawnAgentExecutionResult> executeSpawnAgent(
            ToolExecutionRequest request, SpawnAgentParams params,
            String sessionId,
            UserProfile userContext,
            QueryLoopState parentState,
            TraceAgentDescriptor subAgentDescriptor,
            String subAgentStepId) {

        return subGraphNode.execute(
                params.prompt(),
                sessionId,
                params.subagentType(),
                params.permittedTools(),
                params.maxTurns(),
                userContext,
                parentState.getTraceRunId(),
                subAgentDescriptor
        ).thenApply(subAgentResult -> {
            String formattedResult = formatSubAgentResult(params.prompt(), subAgentResult);
            ToolExecutionResultMessage resultMsg = ToolExecutionResultMessage.from(request, formattedResult);
            return new SpawnAgentExecutionResult(resultMsg, request, subAgentResult.isSuccess(), subAgentResult, subAgentDescriptor, subAgentStepId, params.prompt());
        }).exceptionally(e -> {
            log.error("[SpawnAgentStrategy] 子Agent执行异常", e);
            String errorMsg = String.format("[子Agent执行异常]\n任务：%s\n错误：%s",
                    params.prompt(), e.getMessage());
            ToolExecutionResultMessage resultMsg = ToolExecutionResultMessage.from(request, errorMsg);
            return new SpawnAgentExecutionResult(resultMsg, request, false, null, subAgentDescriptor, subAgentStepId, params.prompt());
        });
    }

    /** 把子 Agent 的结构化结果格式化成主 Agent 能直接阅读的文本摘要。 */
    private String formatSubAgentResult(String prompt, SubAgentResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("[子Agent执行完成]\n");
        sb.append("任务：").append(prompt).append("\n");
        sb.append("状态：").append(switch (result.status()) {
            case "success" -> "成功";
            case "max_turns_exceeded" -> "超时（达到最大轮次限制）";
            case "error" -> "失败";
            default -> result.status();
        }).append("\n");
        sb.append("轮次：").append(result.turnCount()).append("/").append(result.maxTurns()).append("\n");
        sb.append("Token用量：输入").append(result.inputTokens()).append(", 输出").append(result.outputTokens()).append("\n\n");
        sb.append("结果摘要：\n").append(result.summary());
        return sb.toString();
    }

    // ========== 内部记录类型 ==========

    /**
     * spawnAgent 参数 DTO
     */
    private record SpawnAgentParams(
            String prompt,
            SubAgentType subagentType,
            Set<String> permittedTools,
            int maxTurns
    ) {}

    /**
     * spawnAgent 执行结果包装
     */
    private record SpawnAgentExecutionResult(
            ToolExecutionResultMessage resultMessage,
            ToolExecutionRequest request,
            boolean success,
            SubAgentResult subAgentResult,
            TraceAgentDescriptor subAgentDescriptor,
            String subAgentStepId,
            String taskDescription
    ) {}

}
