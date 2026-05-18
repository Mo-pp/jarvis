package com.msz.resume.ai.chat.runtime.node.inner.strategy;

import com.msz.resume.ai.chat.runtime.trace.TraceService;
import com.msz.resume.ai.chat.runtime.trace.ArtifactActionEventService;
import com.msz.resume.ai.chat.runtime.trace.AssistantCheckpointService;
import com.msz.resume.ai.chat.runtime.trace.ToolActionEventService;
import com.msz.resume.ai.hook.HookContext;
import com.msz.resume.ai.hook.HookEngine;
import com.msz.resume.ai.chat.runtime.state.QueryLoopState;
import com.msz.resume.ai.tool.ToolRuntimeContext;
import com.msz.resume.ai.chat.tooling.TaskPlanTool;
import com.msz.resume.ai.tool.registry.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 普通工具执行策略。
 *
 * 作用：处理除 spawnAgent 等特殊工具外的常规工具调用，
 * 并把开始、进度、成功、失败、artifact_ready、任务计划等事件同步打进 trace。
 * 可以把它理解成“标准流水线”，绝大多数工具都按这套节奏执行和回传。
 *
 * 代码逻辑：
 * 1. 设置 ToolRuntimeContext，让工具执行时能拿到 session / agent / run 信息
 * 2. 逐个执行工具，前后补发 trace 和 action 事件
 * 3. 执行 PostToolUse Hook，并处理 toolSearch / createPlan / publishArtifact 这些特殊结果
 * 4. 汇总工具结果、任务计划和发现的工具，返回给 ExecuteToolNode
 */
@Slf4j
@Component
public class NormalToolStrategy implements ToolExecutionStrategy {

    private final ToolRegistry toolRegistry;
    private final HookEngine hookEngine;
    private final TraceService traceService;
    private final ToolActionEventService toolActionEventService;
    private final ArtifactActionEventService artifactActionEventService;
    private final AssistantCheckpointService assistantCheckpointService;

    /** 注入普通工具执行、trace 事件和任务检查点所需依赖。 */
    public NormalToolStrategy(ToolRegistry toolRegistry,
                              HookEngine hookEngine,
                              TraceService traceService,
                              ToolActionEventService toolActionEventService,
                              ArtifactActionEventService artifactActionEventService,
                              AssistantCheckpointService assistantCheckpointService) {
        this.toolRegistry = toolRegistry;
        this.hookEngine = hookEngine;
        this.traceService = traceService;
        this.toolActionEventService = toolActionEventService;
        this.artifactActionEventService = artifactActionEventService;
        this.assistantCheckpointService = assistantCheckpointService;
    }

    @Override
    /** 作为兜底策略，默认认为任何工具都可以先接住。 */
    public boolean supports(ToolExecutionRequest request) {
        // 支持所有工具（作为兜底策略）
        return true;
    }

    @Override
    /** 返回最低优先级，确保只有没有更专门策略时才走这里。 */
    public int getPriority() {
        // 最低优先级，作为默认策略
        return 1000;
    }

    @Override
    /** 顺序执行一批普通工具，并把结果包装成下一轮 LLM 能继续理解的上下文。 */
    public ToolExecutionResult execute(ToolExecutionContext context) {
        QueryLoopState state = context.state();
        List<ToolExecutionRequest> requests = context.requests();

        List<ToolExecutionResultMessage> results = new ArrayList<>(context.blockedResults());
        List<ToolExecutionRequest> contexts = new ArrayList<>(context.blockedRequests());
        Set<String> discoveredTools = new LinkedHashSet<>(state.getDiscoveredTools());
        String transition = ToolExecutionResult.TRANSITION_SUCCESS;
        boolean artifactPublished = false;

        List<Map<String, Object>> taskPlan;
        TaskPlanTool.initTasks(state.getTaskPlan());
        ToolRuntimeContext.setSessionId(state.getSessionId());
        ToolRuntimeContext.setOpenVikingIdentity(context.openVikingIdentity());
        ToolRuntimeContext.setRunId(state.getTraceRunId());
        ToolRuntimeContext.setAgentId(state.getTraceAgentId());
        ToolRuntimeContext.setAgentLabel(state.getTraceAgentLabel());

        try {
            for (ToolExecutionRequest req : requests) {
                try {
                    if (context.traceContext() != null) {
                        traceService.startToolCall(context.traceContext(), context.agentDescriptor(), req);
                    }
                    toolActionEventService.toolStarted(context.traceContext(), context.agentDescriptor(), req);
                    dev.langchain4j.service.tool.ToolExecutor toolExecutor = toolRegistry.getToolExecutor(req.name());
                    if (toolExecutor == null) {
                        String errorMsg = "工具执行失败：未找到工具 '" + req.name() + "'";
                        log.warn("[NormalToolStrategy] {}", errorMsg);
                        results.add(ToolExecutionResultMessage.from(req, errorMsg));
                        contexts.add(req);
                        transition = ToolExecutionResult.TRANSITION_FAILED;
                        toolActionEventService.toolFailed(context.traceContext(), context.agentDescriptor(), req, errorMsg);
                        if (context.traceContext() != null) {
                            traceService.failToolCall(context.traceContext(), context.agentDescriptor(), req);
                        }
                        continue;
                    }

                    log.debug("[NormalToolStrategy] 执行工具: {}", req.name());
                    toolActionEventService.toolProgress(
                            context.traceContext(),
                            context.agentDescriptor(),
                            req,
                            progressSummary(req.name())
                    );
                    String toolResult = toolExecutor.execute(req, null);

                    // PostToolUse Hook 回调
                    HookContext hookCtx = new HookContext(
                            req.name(), req.arguments(),
                            state, state.getSessionId(), req.id(), state.isSubAgent()
                    );
                    toolResult = hookEngine.postToolUse(hookCtx, toolResult);

                    // 如果是 toolSearch，解析发现的工具
                    if ("toolSearch".equals(req.name())) {
                        discoveredTools.addAll(parseToolSearchResult(toolResult));
                    }
                    if ("createPlan".equals(req.name())) {
                        assistantCheckpointService.taskPlanCreated(
                                context.traceContext(),
                                context.agentDescriptor(),
                                TaskPlanTool.getCurrentTasks().size()
                        );
                    }

                    results.add(ToolExecutionResultMessage.from(req, toolResult));
                    contexts.add(req);
                    if (context.traceContext() != null) {
                        traceService.completeToolCall(context.traceContext(), context.agentDescriptor(), req);
                    }
                    toolActionEventService.toolSucceeded(context.traceContext(), context.agentDescriptor(), req, toolResult);
                    if ("publishArtifact".equals(req.name()) && isPublishedArtifact(toolResult)) {
                        transition = ToolExecutionResult.TRANSITION_ARTIFACT_READY;
                        artifactPublished = true;
                        artifactActionEventService.artifactReady(context.traceContext(), context.agentDescriptor(), req, toolResult);
                    }

                } catch (Exception e) {
                    String errorMsg = String.format("工具执行失败：[%s] %s",
                            e.getClass().getSimpleName(), e.getMessage());
                    log.error("[NormalToolStrategy] 工具执行异常: {}", req.name(), e);
                    results.add(ToolExecutionResultMessage.from(req, errorMsg));
                    contexts.add(req);
                    transition = ToolExecutionResult.TRANSITION_FAILED;
                    toolActionEventService.toolFailed(context.traceContext(), context.agentDescriptor(), req, errorMsg);
                    if (context.traceContext() != null) {
                        traceService.failToolCall(context.traceContext(), context.agentDescriptor(), req);
                    }
                }
            }
        } finally {
            ToolRuntimeContext.clear();
        }

        taskPlan = artifactPublished
                ? TaskPlanTool.completeUnfinishedTasks()
                : TaskPlanTool.getCurrentTasks();
        TaskPlanTool.clearTasks();

        return ToolExecutionResult.builder()
                .messages(results)
                .contexts(contexts)
                .transition(transition)
                .discoveredTools(discoveredTools)
                .taskPlan(taskPlan)
                .build();
    }

    /**
     * 解析 toolSearch 返回结果，提取发现的延迟工具名称
     */
    private Set<String> parseToolSearchResult(String toolResult) {
        Set<String> discovered = new LinkedHashSet<>();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(toolResult);
            if (root.has("name")) {
                String toolName = root.get("name").asText();
                if (toolRegistry.getDeferredToolSpecification(toolName) != null) {
                    discovered.add(toolName);
                }
            }
        } catch (Exception e) {
            // 不是 JSON 格式，忽略
            log.debug("[NormalToolStrategy] toolSearch 结果解析失败: {}", e.getMessage());
        }
        return discovered;
    }

    /** 判断 publishArtifact 的返回结果是不是一次真正成功的产物发布。 */
    private boolean isPublishedArtifact(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return false;
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(toolResult);
            String type = root.path("type").asText("");
            return !type.isBlank() && !"error".equals(type);
        } catch (Exception e) {
            log.debug("[NormalToolStrategy] publishArtifact 结果解析失败: {}", e.getMessage());
            return false;
        }
    }

    /** 给部分常用工具补一条人能看懂的执行进度摘要。 */
    private String progressSummary(String toolName) {
        return switch (toolName) {
            case "openviking_tree" -> "正在遍历目录树，整理资源结构";
            case "openviking_list" -> "正在读取目录列表";
            case "openviking_read" -> "正在读取资源内容";
            case "openviking_grep" -> "正在扫描内容匹配项";
            case "openviking_glob" -> "正在按路径模式扫描候选资源";
            case "openviking_find", "openviking_search" -> "正在检索相关知识";
            case "publishArtifact" -> "正在发布到工作台";
            case "generateMindmap" -> "正在生成结构图";
            case "getResumeGuide", "getOptimizeGuide" -> "正在读取生成规则";
            case "toolSearch" -> "正在加载工具说明";
            default -> null;
        };
    }
}
