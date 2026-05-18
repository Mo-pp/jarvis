package com.msz.resume.ai.chat.runtime.trace;

import com.msz.resume.ai.chat.runtime.subagent.SubAgentResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 子 Agent 委托事件服务。
 *
 * 作用：把 `spawnAgent` 这类委托过程转成前端能读懂的时间线事件，
 * 比如“已经委托给某个子 Agent”“子 Agent 已完成”“子 Agent 出错了”。
 * 可以把它理解成“外包任务进度播报器”。
 */
@Service
public class DelegationActionEventService {

    private final TimelineActionService timelineActionService;

    /** 注入时间线服务，用于统一构造子 Agent 委托动作。 */
    public DelegationActionEventService(TimelineActionService timelineActionService) {
        this.timelineActionService = timelineActionService;
    }

    /** 发布委托开始事件，告诉前端这项任务已经交给子 Agent 处理。 */
    public void delegationStarted(ChatRunTraceContext traceContext,
                                  TraceAgentDescriptor subAgentDescriptor,
                                  ToolExecutionRequest request,
                                  String taskDescription) {
        publish(traceContext, "delegation_started", buildPayload(
                traceContext, subAgentDescriptor, request, taskDescription, "running", "委托任务已开始", null, null));
    }

    /** 发布委托成功事件，并把子 Agent 的轮次和 token 摘要带给前端。 */
    public void delegationSucceeded(ChatRunTraceContext traceContext,
                                    TraceAgentDescriptor subAgentDescriptor,
                                    ToolExecutionRequest request,
                                    String taskDescription,
                                    SubAgentResult result) {
        publish(traceContext, "delegation_result", buildPayload(
                traceContext,
                subAgentDescriptor,
                request,
                taskDescription,
                result != null && result.isMaxTurnsExceeded() ? "pending" : "success",
                resultSummary(result),
                null,
                result));
    }

    /** 发布委托失败事件，让用户知道失败是出在子 Agent 这条支线上。 */
    public void delegationFailed(ChatRunTraceContext traceContext,
                                 TraceAgentDescriptor subAgentDescriptor,
                                 ToolExecutionRequest request,
                                 String taskDescription,
                                 String error) {
        publish(traceContext, "delegation_error", buildPayload(
                traceContext, subAgentDescriptor, request, taskDescription, "failed", null, error, null));
    }

    /** 构造统一的委托 payload，把标题、摘要、错误和统计信息打包好。 */
    private Map<String, Object> buildPayload(ChatRunTraceContext traceContext,
                                             TraceAgentDescriptor subAgentDescriptor,
                                             ToolExecutionRequest request,
                                             String taskDescription,
                                             String status,
                                             String summary,
                                             String error,
                                             SubAgentResult result) {
        String delegationId = delegationId(request);
        TimelineActionService.TimelineActionBuilder builder = timelineActionService
                .builder(delegationId, traceContext, subAgentDescriptor, TimelineActionService.AgentDefaults.subAgent())
                .toolCallId(request != null ? request.id() : null)
                .title(subAgentDescriptor != null ? "委托给 " + subAgentDescriptor.agentLabel() : "委托子 Agent")
                .status(status)
                .summary(summary)
                .error(error != null ? truncate(error, 220) : "")
                .put("agentType", subAgentDescriptor != null && subAgentDescriptor.subAgentType() != null ? subAgentDescriptor.subAgentType() : "")
                .put("task", taskDescription != null ? truncate(taskDescription, 220) : "");
        if (result != null) {
            builder.put("turnCount", result.turnCount())
                    .put("maxTurns", result.maxTurns())
                    .put("inputTokens", result.inputTokens())
                    .put("outputTokens", result.outputTokens());
        }
        return builder.build();
    }

    /** 发布委托事件，统一走 TimelineActionService。 */
    private void publish(ChatRunTraceContext traceContext, String type, Map<String, Object> payload) {
        timelineActionService.publish(traceContext, type, payload, "DelegationActionEventService");
    }

    /** 生成委托动作 ID，让同一条委托在时间线里能被持续更新。 */
    private String delegationId(ToolExecutionRequest request) {
        if (request != null && request.id() != null && !request.id().isBlank()) {
            return "delegation_" + request.id();
        }
        return "delegation_" + Integer.toHexString(System.identityHashCode(request));
    }

    /** 根据子 Agent 结果生成一句面向用户的摘要。 */
    private String resultSummary(SubAgentResult result) {
        if (result == null) {
            return "子 Agent 已返回";
        }
        if (result.isMaxTurnsExceeded()) {
            return "子 Agent 已达到最大轮次，返回当前进展";
        }
        return "子 Agent 已完成，返回结果摘要";
    }

    /** 截断过长文本，避免任务描述或错误在时间线卡片里爆炸。 */
    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
