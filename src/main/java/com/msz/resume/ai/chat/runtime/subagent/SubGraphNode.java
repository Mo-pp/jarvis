package com.msz.resume.ai.chat.runtime.subagent;

import com.msz.resume.ai.agent.SubAgentType;
import com.msz.resume.ai.chat.runtime.trace.TraceAgentDescriptor;
import com.msz.resume.ai.integrations.openviking.core.context.OpenVikingIdentitySupport;
import com.msz.resume.ai.integrations.openviking.core.model.OpenVikingIdentity;
import com.msz.resume.ai.chat.prompt.model.UserProfile;
import com.msz.resume.ai.chat.runtime.state.QueryLoopState;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 子 Agent 子图执行器。
 *
 * 作用：承接 spawnAgent 派发出来的子任务，创建独立的 QueryLoopState 子图执行环境，
 * 跑完后把摘要、状态和 token 用量打包回主 Agent。
 * 可以把它理解成“分身工位”，主 Agent 把任务扔过来，这里单独开一套小循环做完再回报。
 *
 * 代码逻辑：
 * 1. 根据父图传入的任务、工具白名单和 trace 信息组装子 Agent 初始状态
 * 2. 复用现有 queryLoopGraph 编译出子图，并设置更贴合子任务的递归上限
 * 3. 让子图独立执行，不把内部 message history 泄露回父图
 * 4. 从最终状态提取摘要、轮次和 token 用量，封装成 SubAgentResult 返回
 */
@Slf4j
@Component
public class SubGraphNode {

    private static final int SUB_AGENT_RECURSION_LIMIT_BUFFER = 4;

    private final StateGraph<QueryLoopState> queryLoopGraph;

    /**
     * @Lazy 打破循环依赖：queryLoopGraph → executeToolNode → subGraphNode → queryLoopGraph
     * SubGraphNode 仅在 execute() 运行时才需要编译图，构造时不需要，因此延迟注入是安全的。
     */
    /** 延迟注入内层图定义，避免和 ExecuteToolNode 形成循环依赖。 */
    public SubGraphNode(@Lazy StateGraph<QueryLoopState> queryLoopGraph) {
        this.queryLoopGraph = queryLoopGraph;
    }

    /**
     * 执行子Agent任务
     *
     * @param taskDescription 任务描述
     * @param parentSessionId 父会话ID（用于日志追踪）
     * @param agentType       Agent类型（GENERAL/PLAN/EXPLORE）
     * @param customTools     自定义工具名称集合（仅GENERAL类型使用）
     * @param maxTurns        最大迭代轮次
     * @param userContext     用户上下文
     * @return 子Agent执行结果
     */
    public CompletableFuture<SubAgentResult> execute(
            String taskDescription,
            String parentSessionId,
            SubAgentType agentType,
            Set<String> customTools,
            int maxTurns,
            UserProfile userContext,
            String traceRunId,
            TraceAgentDescriptor agentDescriptor) {
        OpenVikingIdentity identity = OpenVikingIdentitySupport.fromUserProfile(userContext);

        return OpenVikingIdentitySupport.supplyAsync(identity, () -> {
            long startTime = System.currentTimeMillis();
            log.info("[SubGraphNode] 开始执行子Agent任务: sessionId={}, agentType={}, maxTurns={}",
                    parentSessionId, agentType, maxTurns);

            try {
                // 1. 构造子Agent的初始 QueryLoopState
                Map<String, Object> initialState = buildSubAgentInitialState(
                        taskDescription, parentSessionId, agentType, customTools, maxTurns, userContext, traceRunId, agentDescriptor);

                // 2. 编译并流式执行内层图
                CompileConfig compileConfig = CompileConfig.builder()
                        .recursionLimit(subAgentRecursionLimit(maxTurns))
                        .build();
                CompiledGraph<QueryLoopState> compiledGraph = queryLoopGraph.compile(compileConfig);

                QueryLoopState finalState = null;
                for (var output : compiledGraph.stream(initialState)) {
                    log.debug("[SubGraphNode] 步骤: {}, sessionId={}", output.node(), parentSessionId);
                    finalState = output.state();
                }

                if (finalState == null) {
                    log.error("[SubGraphNode] 子Agent执行异常：未获得最终状态, sessionId={}", parentSessionId);
                    return SubAgentResult.error("子Agent执行异常：未获得最终状态", 0, maxTurns, 0, 0);
                }

                // 3. 提取结果
                SubAgentResult result = extractResult(finalState, taskDescription, maxTurns);

                long elapsed = System.currentTimeMillis() - startTime;
                log.info("[SubGraphNode] 子Agent任务完成: sessionId={}, status={}, turns={}, tokens={}/{}, elapsed={}ms",
                        parentSessionId, result.status(), result.turnCount(),
                        result.inputTokens(), result.outputTokens(), elapsed);

                return result;

            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.error("[SubGraphNode] 子Agent任务异常: sessionId={}, elapsed={}ms",
                        parentSessionId, elapsed, e);
                return SubAgentResult.error("子Agent执行异常: " + e.getMessage(), 0, maxTurns, 0, 0);
            }
        });
    }

    /**
     * 构造子Agent的初始状态
     */
    private Map<String, Object> buildSubAgentInitialState(
            String taskDescription,
            String parentSessionId,
            SubAgentType agentType,
            Set<String> customTools,
            int maxTurns,
            UserProfile userContext,
            String traceRunId,
            TraceAgentDescriptor agentDescriptor) {

        Map<String, Object> state = new HashMap<>();
        state.put(QueryLoopState.SESSION_ID, parentSessionId + "-sub");
        state.put(QueryLoopState.IS_SUB_AGENT, true);
        state.put(QueryLoopState.SUB_AGENT_TYPE, agentType);
        state.put(QueryLoopState.MAX_TURNS, maxTurns);
        state.put(QueryLoopState.SUB_AGENT_TASK, taskDescription);
        state.put(QueryLoopState.USER_CONTEXT, userContext);
        state.put(QueryLoopState.OPENVIKING_IDENTITY, OpenVikingIdentitySupport.fromUserProfile(userContext));
        state.put(QueryLoopState.TRACE_RUN_ID, traceRunId);
        state.put(QueryLoopState.TRACE_AGENT_ID, agentDescriptor != null ? agentDescriptor.agentId() : "sub");
        state.put(QueryLoopState.TRACE_AGENT_LABEL, agentDescriptor != null ? agentDescriptor.agentLabel() : "Sub Agent");
        state.put(QueryLoopState.TRACE_AGENT_SCOPE, "sub");

        // AVAILABLE_TOOLS: 仅GENERAL模式支持自定义工具
        if (agentType.supportsCustomTools() && customTools != null && !customTools.isEmpty()) {
            state.put(QueryLoopState.AVAILABLE_TOOLS, new LinkedHashSet<>(customTools));
        } else {
            state.put(QueryLoopState.AVAILABLE_TOOLS, new LinkedHashSet<String>());
        }

        // 子Agent不需要已发现的延迟工具——所有允许的工具通过agentType解析
        state.put(QueryLoopState.DISCOVERED_TOOLS, new LinkedHashSet<String>());

        // 注入任务描述作为 UserMessage
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from(taskDescription));
        state.put(QueryLoopState.MESSAGE_HISTORY, messages);

        return state;
    }

    /**
     * 从子Agent最终状态提取结果
     */
    private SubAgentResult extractResult(QueryLoopState finalState, String taskDescription, int maxTurns) {
        int turnCount = finalState.getTurnCount();
        int inputTokens = finalState.getSubAgentInputTokens();
        int outputTokens = finalState.getSubAgentOutputTokens();

        // 提取最终AI消息作为摘要
        String summary = extractFinalAiMessage(finalState);
        if (summary == null || summary.isBlank()) {
            summary = "子Agent未生成有效回复";
        }

        // 判断是否因轮次超限终止
        if (turnCount >= maxTurns) {
            return SubAgentResult.maxTurnsExceeded(summary, turnCount, maxTurns, inputTokens, outputTokens);
        }

        return SubAgentResult.success(summary, turnCount, maxTurns, inputTokens, outputTokens);
    }

    /**
     * 提取子Agent最终AI消息内容
     */
    private String extractFinalAiMessage(QueryLoopState state) {
        List<ChatMessage> messages = state.getMessages();
        // 倒序查找最后一条AI文本消息
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AiMessage aiMessage) {
                if (aiMessage.text() != null && !aiMessage.text().isBlank()) {
                    return aiMessage.text();
                }
            }
        }
        return null;
    }

    /** 按子 Agent 最大轮次推导一个更保守的递归上限。 */
    private int subAgentRecursionLimit(int maxTurns) {
        if (maxTurns <= 0) {
            return 100;
        }
        return Math.max(25, maxTurns * 2 + SUB_AGENT_RECURSION_LIMIT_BUFFER);
    }
}
