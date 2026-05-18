package com.msz.resume.ai.chat.runtime.node.inner;

import com.msz.resume.ai.agent.SubAgentType;
import com.msz.resume.ai.agent.SubAgentTypeRegistry;
import com.msz.resume.ai.chat.observability.cache.CacheTracker;
import com.msz.resume.ai.chat.compression.MessagePreprocessingPipeline;
import com.msz.resume.ai.chat.compression.PostCompactRestorer;
import com.msz.resume.ai.chat.compression.TokenEstimator;
import com.msz.resume.ai.chat.compression.model.CacheUsage;
import com.msz.resume.ai.chat.compression.model.PipelineResult;
import com.msz.resume.ai.chat.runtime.trace.ChatRunTraceContext;
import com.msz.resume.ai.chat.runtime.trace.ChatStreamContext;
import com.msz.resume.ai.chat.runtime.trace.AssistantCheckpointService;
import com.msz.resume.ai.chat.runtime.trace.TraceAgentDescriptor;
import com.msz.resume.ai.chat.runtime.trace.TraceService;
import com.msz.resume.ai.integrations.openviking.core.context.OpenVikingIdentitySupport;
import com.msz.resume.ai.integrations.openviking.core.model.OpenVikingIdentity;
import com.msz.resume.ai.integrations.openviking.core.recall.OpenVikingRecallEngine;
import com.msz.resume.ai.integrations.openviking.core.recall.OpenVikingRecallResult;
import com.msz.resume.ai.integrations.openviking.core.session.OpenVikingSessionGateway;
import com.msz.resume.ai.integrations.openviking.core.session.OpenVikingSessionProperties;
import com.msz.resume.ai.chat.prompt.builder.SystemPromptBuilder;
import com.msz.resume.ai.chat.prompt.model.PromptResult;
import com.msz.resume.ai.chat.runtime.state.QueryLoopState;
import com.msz.resume.ai.tool.registry.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM 调用节点。
 *
 * 作用：负责把当前 QueryLoopState 里的上下文、提示词、工具规格组装成一次 LLM 请求，
 * 并把回复重新写回状态，还顺手把 recall、tool plan、缓存和 trace 一起记上。
 * 可以把它理解成“主脑发问器”，每轮真正去问模型、拿回下一步意图的就是它。
 *
 * 代码逻辑：
 * 1. 先跑消息预处理和压缩，必要时补入 OpenViking session context
 * 2. 记录自动召回 trace，并根据主 Agent / 子 Agent 模式选择不同提示词和工具集
 * 3. 调 LLM，同步或流式拿回回复
 * 4. 如果回复里带工具计划，就提前发 assistant checkpoint 和 trace tool plan
 * 5. 把 token、cache、trace 元数据重新塞回 QueryLoopState，供后续节点继续使用
 *
 * @see SystemPromptBuilder
 * @see ToolRegistry
 */

@Slf4j
@Component
public class CallLlmNode implements AsyncNodeAction<QueryLoopState> {

    /** LLM 聊天模型，用于发送请求和接收回复 */
    private final ChatModel chatModel;

    /** 可选的流式聊天模型，用于 SSE 打字机输出 */
    private final Optional<StreamingChatModel> streamingChatModel;

    /** 工具注册表，用于获取工具规格（发给 LLM） */
    private final ToolRegistry toolRegistry;

    /** 系统提示词构建器，用于生成完整的系统提示词 */
    private final SystemPromptBuilder promptBuilder;

    /** 缓存追踪器，用于追踪 LLM API 的缓存命中情况 */
    private final CacheTracker cacheTracker;

    /** 消息预处理管线，用于上下文压缩 */
    private final MessagePreprocessingPipeline pipeline;

    /** Token 估算器，用于锚点法估算 */
    private final TokenEstimator tokenEstimator;

    /** 子Agent类型注册中心，用于根据类型获取工具规格 */
    private final SubAgentTypeRegistry subAgentTypeRegistry;

    /** OpenViking Session Gateway，用于读取 Session Context */
    private final OpenVikingSessionGateway openVikingSessionGateway;

    /** OpenViking Session 配置 */
    private final OpenVikingSessionProperties openVikingSessionProperties;
    private final OpenVikingRecallEngine openVikingRecallEngine;
    private final TraceService traceService;
    private final AssistantCheckpointService assistantCheckpointService;


    /** 注入 LLM 调用、提示词构建、召回和 trace 记录所需依赖。 */
    public CallLlmNode(ChatModel chatModel,
                       Optional<StreamingChatModel> streamingChatModel,
                       ToolRegistry toolRegistry,
                       SystemPromptBuilder promptBuilder,
                       CacheTracker cacheTracker,
                       MessagePreprocessingPipeline pipeline,
                       TokenEstimator tokenEstimator,
                       SubAgentTypeRegistry subAgentTypeRegistry,
                       OpenVikingSessionGateway openVikingSessionGateway,
                       OpenVikingSessionProperties openVikingSessionProperties,
                       OpenVikingRecallEngine openVikingRecallEngine,
                       TraceService traceService,
                       AssistantCheckpointService assistantCheckpointService) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.toolRegistry = toolRegistry;
        this.promptBuilder = promptBuilder;
        this.cacheTracker = cacheTracker;
        this.pipeline = pipeline;
        this.tokenEstimator = tokenEstimator;
        this.subAgentTypeRegistry = subAgentTypeRegistry;
        this.openVikingSessionGateway = openVikingSessionGateway;
        this.openVikingSessionProperties = openVikingSessionProperties;
        this.openVikingRecallEngine = openVikingRecallEngine;
        this.traceService = traceService;
        this.assistantCheckpointService = assistantCheckpointService;
    }

    /**
     * 节点的执行方法，LangGraph 会调用这个
     *
     * @param currentState 当前的状态，包含消息列表、轮次等
     * @return 要更新的变量 Map，包含：
     *         - MESSAGES: LLM 的回复消息
     *         - TURN_COUNT: 轮次 +1
     *         - TRANSITION: 状态转移标记
     *         - LAST_OUTPUT_TOKEN_COUNT: 输出 token 数
     */
    @Override
    public CompletableFuture<Map<String, Object>> apply(QueryLoopState currentState) {

        OpenVikingIdentity identity = OpenVikingIdentitySupport.fromQueryLoopState(currentState);
        return OpenVikingIdentitySupport.supplyAsync(identity, () -> {

            try {

                // 1. 消息预处理管线：检查上下文利用率，按需执行压缩
                List<ChatMessage> stateMessages = currentState.getMessages();
                // 设置 taskPlan ThreadLocal，供 PostCompactRestorer 在 L5 压缩后恢复
                PostCompactRestorer.setTaskPlan(currentState.getTaskPlan());
                PipelineResult pipelineResult;
                try {
                    pipelineResult = pipeline.process(stateMessages, currentState.getSessionId());
                } finally {
                    PostCompactRestorer.clearTaskPlan();
                }

                if (pipelineResult.wasCompressed()) {
                    log.info("[CallLlmNode] 执行压缩: {}, tokens: {} → {}",
                            pipelineResult.executedLevels(),
                            pipelineResult.originalTokens(),
                            pipelineResult.finalTokens());
                }
                List<ChatMessage> processedMessages = pipelineResult.messages();

                // 1.5 压缩后加载 OpenViking Session Context（如果启用且发生了压缩）
                if (pipelineResult.wasCompressed() && shouldLoadSessionContext()) {
                    try {
                        String sessionId = currentState.getSessionId();
                        Optional<String> sessionContext = openVikingSessionGateway.loadSessionContext(
                                sessionId,
                                openVikingSessionProperties.getContextTokenBudget(),
                                identity);

                        if (sessionContext.isPresent() && !sessionContext.get().isBlank()) {
                            // 将 Session Context 作为用户消息注入（作为上下文参考）
                            String contextMessage = formatSessionContextMessage(sessionContext.get());
                            processedMessages = new ArrayList<>(processedMessages);
                            processedMessages.add(0, UserMessage.from(contextMessage));
                            log.info("[CallLlmNode] 注入 OpenViking Session Context: sessionId={}, length={}",
                                    sessionId, sessionContext.get().length());
                        }
                    } catch (Exception e) {
                        log.warn("[CallLlmNode] 加载 Session Context 失败: {}", e.getMessage());
                    }
                }

                // 1.6 OpenViking 自动召回 Phase 1：仅执行触发/跳过决策并写入 Trace，不做网络检索
                TraceAgentDescriptor agentDescriptor = new TraceAgentDescriptor(
                        currentState.getTraceAgentId(),
                        currentState.getTraceAgentScope(),
                        currentState.getTraceAgentLabel(),
                        currentState.isSubAgent() ? currentState.getSubAgentType().name() : null
                );
                ChatRunTraceContext traceContext = ChatStreamContext.getTraceContext(
                        currentState.getSessionId(), currentState.getTraceRunId());
                OpenVikingRecallResult recallResult = null;
                if (!currentState.isSubAgent()) {
                    recallResult = openVikingRecallEngine.prepare(currentState, processedMessages);
                    processedMessages = openVikingRecallEngine.inject(processedMessages, recallResult);
                    traceService.recordContextRecall(
                            traceContext,
                            agentDescriptor,
                            recallResult.status(),
                            switch (recallResult.status()) {
                                case "injected" -> "success";
                                case "failed" -> "failed";
                                case "triggered" -> "running";
                                default -> "skipped";
                            },
                            recallResult.toTraceMeta()
                    );
                }

                // 2. 根据子Agent模式构建系统提示词和工具规格
                boolean isSubAgent = currentState.isSubAgent();
                String systemPrompt;
                List<ToolSpecification> toolSpecs;// 工具规格

                if (isSubAgent) {
                    // 子Agent模式：使用精简提示词（复用父级静态前缀，缓存命中）
                    // 和受限工具集（根据agentType配置）
                    systemPrompt = buildSubAgentPrompt(currentState);

                    SubAgentType agentType = currentState.getSubAgentType();
                    toolSpecs = subAgentTypeRegistry.getToolSpecifications(
                            agentType,
                            toolRegistry,
                            currentState.getAvailableTools());
                    log.info("[CallLlmNode] 子Agent模式: 类型={}, 任务={}, 工具数={}",
                            agentType, currentState.getSubAgentTask(), toolSpecs.size());
                } else {
                    // 父图模式：使用完整系统提示词和全部工具
                    systemPrompt = buildSystemPrompt(currentState.getUserContext());
                    toolSpecs = toolRegistry.getAllSpecifications(currentState.getDiscoveredTools());
                }

                // 3. 构建请求消息列表：在开头注入系统提示消息
                List<ChatMessage> requestMessages = new ArrayList<>();
                requestMessages.add(SystemMessage.from(systemPrompt));
                requestMessages.addAll(processedMessages);

                // 打印调试日志：发给LLM的消息统计（不打印完整内容）
                log.info("[CallLlmNode] 发送消息: 共{}条, 预处理={}, 模式={}",
                        requestMessages.size(),
                        pipelineResult.wasCompressed() ? "已压缩" : "无变化",
                        isSubAgent ? "子Agent" : "父图");

                // 详细消息列表使用 DEBUG 级别，且截断内容
                if (log.isDebugEnabled()) {
                    for (int i = 0; i < requestMessages.size(); i++) {
                        ChatMessage msg = requestMessages.get(i);
                        log.debug("[CallLlmNode]   [{}] type={}, summary={}",
                                i, msg.type(), MessageLogUtils.summarizeMessage(msg));
                    }
                }

                // 4. 构建请求：消息列表 + 工具规格
                ChatRequest request = ChatRequest.builder()
                        .messages(requestMessages)
                        .toolSpecifications(toolSpecs)
                        .build();

                // 5. 调用大模型，拿到回复
                ChatResponse response = chat(request, currentState.getSessionId());
                AiMessage aiMessage = response.aiMessage();
                if (traceContext != null && aiMessage.hasToolExecutionRequests()) {
                    assistantCheckpointService.toolPlan(
                            traceContext,
                            agentDescriptor,
                            currentState.getMessages(),
                            aiMessage.toolExecutionRequests(),
                            currentState.getTurnCount() + 1
                    );
                    String batchStepId = traceService.startToolBatch(traceContext, agentDescriptor, aiMessage.toolExecutionRequests());
                    for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                        traceService.planToolCall(traceContext, agentDescriptor, batchStepId, toolRequest);
                    }
                }

                // 打印LLM的原始回复，便于调试
                log.info("[CallLlmNode] LLM原始回复：text={}, hasToolCalls={}",
                        aiMessage.text() != null ? MessageLogUtils.truncate(aiMessage.text(), 200) : "null",
                        aiMessage.hasToolExecutionRequests());

                // 6. 提取本次LLM的token数
                int inputTokenCount = 0;
                int outputTokenCount = 0;
                if (response.tokenUsage() != null) {
                    if (response.tokenUsage().inputTokenCount() != null) {
                        inputTokenCount = response.tokenUsage().inputTokenCount();
                    }
                    if (response.tokenUsage().outputTokenCount() != null) {
                        outputTokenCount = response.tokenUsage().outputTokenCount();
                    }
                }

                // 7. 更新 TokenEstimator 锚点（用于后续增量估算）
                if (inputTokenCount > 0) {
                    tokenEstimator.updateAnchor(inputTokenCount, requestMessages.size());
                    log.debug("[CallLlmNode] 更新锚点: {} tokens, {} 条消息",
                            inputTokenCount, requestMessages.size());
                }

                // 8. 追踪缓存使用情况
                CacheUsage cacheUsage = cacheTracker.track(response);
                log.info("[CallLlmNode] 缓存: {}%, 热度: {}",
                        String.format("%.1f", cacheUsage.hitRate() * 100),
                        cacheUsage.warmth().getLabel());

                // 连续未命中告警
                if (cacheUsage.shouldAlert()) {
                    log.warn("[CallLlmNode] 缓存连续未命中 {} 次，可能需要检查提示词稳定性",
                            cacheUsage.consecutiveMisses());
                }

                // 9. 构建返回结果
                Map<String, Object> result = new java.util.HashMap<>(Map.of(
                        QueryLoopState.MESSAGE_HISTORY, aiMessage,
                        QueryLoopState.TURN_COUNT, currentState.getTurnCount() + 1,
                        QueryLoopState.TRANSITION, "normal",
                        QueryLoopState.LAST_OUTPUT_TOKEN_COUNT, outputTokenCount,
                        QueryLoopState.CACHE_USAGE, cacheUsage
                ));
                result.put(QueryLoopState.OPENVIKING_IDENTITY, identity);
                result.put(QueryLoopState.TRACE_RUN_ID, currentState.getTraceRunId());
                result.put(QueryLoopState.TRACE_AGENT_ID, currentState.getTraceAgentId());
                result.put(QueryLoopState.TRACE_AGENT_LABEL, currentState.getTraceAgentLabel());
                result.put(QueryLoopState.TRACE_AGENT_SCOPE, currentState.getTraceAgentScope());
                result.put(QueryLoopState.SURFACED_OPENVIKING_URIS,
                        openVikingRecallEngine.mergeSurfacedUris(currentState, recallResult));

                // 子Agent模式：累加 token 用量
                if (isSubAgent) {
                    result.put(QueryLoopState.SUB_AGENT_INPUT_TOKENS,
                            currentState.getSubAgentInputTokens() + inputTokenCount);
                    result.put(QueryLoopState.SUB_AGENT_OUTPUT_TOKENS,
                            currentState.getSubAgentOutputTokens() + outputTokenCount);
                }

                return result;
            } catch (Exception e) {
                LlmErrorType errorType = LlmErrorClassifier.classify(e);
                String errorSummary = LlmErrorClassifier.summarize(e);
                log.error("[CallLlmNode] 调用LLM异常: errorType={}, summary={}",
                        errorType.getValue(), errorSummary, e);
                Map<String, Object> errorResult = new java.util.HashMap<>();
                errorResult.put(QueryLoopState.TRANSITION, "error");
                errorResult.put(QueryLoopState.ERROR_TYPE, errorType.getValue());
                errorResult.put(QueryLoopState.ERROR_MESSAGE, errorSummary);
                errorResult.put(QueryLoopState.TRACE_RUN_ID, currentState.getTraceRunId());
                errorResult.put(QueryLoopState.TRACE_AGENT_ID, currentState.getTraceAgentId());
                errorResult.put(QueryLoopState.TRACE_AGENT_LABEL, currentState.getTraceAgentLabel());
                errorResult.put(QueryLoopState.TRACE_AGENT_SCOPE, currentState.getTraceAgentScope());
                errorResult.put(QueryLoopState.OPENVIKING_IDENTITY, identity);
                return errorResult;
            }

        });


    }

    /**
     * 构建系统提示词（父图模式）
     *
     * 调用 SystemPromptBuilder 生成完整的系统提示词，
     * 包含静态 section（从 YAML 配置加载）和动态 section（如工具使用指南、用户上下文）。
     *
     * @param userProfile 用户上下文，包含用户ID、用户名、语言偏好等信息
     * @return 完整的系统提示词字符串
     */
    private String buildSystemPrompt(com.msz.resume.ai.chat.prompt.model.UserProfile userProfile) {
        PromptResult result = promptBuilder.build(userProfile);
        log.debug("[CallLlmNode] 系统提示词构建完成，预估token数={}", result.tokenEstimate());
        // TRACE级别输出完整系统提示词（需要配置logging.level.com.msz.resume.ai=TRACE）
        log.trace("[CallLlmNode] 完整系统提示词:\n{}", result.systemPrompt());
        return result.systemPrompt();
    }

    /**
     * 构建子Agent系统提示词
     *
     * <p>子Agent复用父级完整静态前缀（intro/tone_and_style/output_efficiency/using_your_tools），
     * 确保 prefix cache 命中。动态部分只包含：
     * <ul>
     *   <li>sub_agent_context（任务描述+约束）</li>
     *   <li>受限版 session_guidance（只展示允许使用的工具）</li>
     *   <li>env_info（模型信息）</li>
     * </ul>
     * 不包含 user_context、user_preferences、memory。
     *
     * @param state 子Agent的查询循环状态
     * @return 子Agent系统提示词字符串
     */
    private String buildSubAgentPrompt(QueryLoopState state) {
        PromptResult result = promptBuilder.buildSubAgent(
                state.getSubAgentTask(),
                state.getUserContext(),
                toolRegistry,
                state.getAvailableTools()
        );
        log.debug("[CallLlmNode] 子Agent提示词构建完成，预估token数={}", result.tokenEstimate());
        log.trace("[CallLlmNode] 子Agent提示词:\n{}", result.systemPrompt());
        return result.systemPrompt();
    }

    /** 在流式和非流式模型之间做统一适配，并尽量把 token delta 推给前端。 */
    private ChatResponse chat(ChatRequest request, String sessionId) {
        if (!ChatStreamContext.isActive(sessionId) || streamingChatModel.isEmpty()) {
            return chatModel.chat(request);
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChatResponse> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicBoolean deltaSendDisabled = new AtomicBoolean(false);

        streamingChatModel.get().chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (deltaSendDisabled.get()) {
                    return;
                }
                try {
                    ChatStreamContext.sendDelta(sessionId, partialResponse);
                } catch (Exception e) {
                    if (deltaSendDisabled.compareAndSet(false, true)) {
                        log.warn("[CallLlmNode] SSE delta 发送失败，停止增量推送但继续等待完整响应: sessionId={}, error={}",
                                sessionId, e.getMessage());
                    }
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                responseRef.set(completeResponse);
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        try {
            if (!latch.await(120, TimeUnit.SECONDS)) {
                throw new RuntimeException(new TimeoutException("Streaming chat request timed out"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for streaming chat response", e);
        }

        Throwable error = errorRef.get();
        if (error != null) {
            throw new RuntimeException("Streaming chat request failed", error);
        }
        ChatResponse response = responseRef.get();
        if (response == null) {
            throw new RuntimeException("Streaming chat returned empty response");
        }
        return response;
    }

    /**
     * 检查是否应该加载 OpenViking Session Context。
     *
     * <p>需要同时满足：主开关启用 + contextOnCompact 启用。
     */
    private boolean shouldLoadSessionContext() {
        return openVikingSessionProperties.isEnabled()
                && openVikingSessionProperties.isContextOnCompact();
    }

    /**
     * 格式化 Session Context 为用户消息。
     *
     * <p>将 OpenViking Session Context 包装为参考信息，帮助 LLM 理解上下文。
     */
    /** 把 OpenViking session context 包成一段“可直接喂给 LLM 的参考消息”。 */
    private String formatSessionContextMessage(String sessionContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("[会话上下文参考]\n");
        sb.append("以下是当前会话的摘要信息，供你理解上下文参考：\n\n");
        sb.append(sessionContext);
        return sb.toString();
    }

}
