package com.msz.resume.ai.chat.runtime.node.inner.strategy;

import com.msz.resume.ai.integrations.openviking.core.model.OpenVikingIdentity;
import com.msz.resume.ai.chat.runtime.state.QueryLoopState;
import com.msz.resume.ai.chat.runtime.trace.ChatRunTraceContext;
import com.msz.resume.ai.chat.runtime.trace.TraceAgentDescriptor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.Collections;
import java.util.List;

/**
 * 工具执行上下文。
 *
 * 作用：把一次工具批次执行需要的状态、身份、trace、请求列表和 Hook 阻断结果打包在一起，
 * 让不同策略都拿到同一份完整输入。
 * 可以把它理解成“工具执行作业单”，谁来执行策略都照着这张单子干。
 *
 * @param state 当前状态机状态
 * @param requests 未被 Hook 阻断的请求列表
 * @param blockedResults Hook 阻断后要直接回给 LLM 的结果消息
 * @param blockedRequests Hook 阻断的原始请求列表
 */
public record ToolExecutionContext(
    QueryLoopState state,
    OpenVikingIdentity openVikingIdentity,
    ChatRunTraceContext traceContext,
    TraceAgentDescriptor agentDescriptor,
    List<ToolExecutionRequest> requests,
    List<ToolExecutionResultMessage> blockedResults,
    List<ToolExecutionRequest> blockedRequests
) {

    /**
     * 获取主要的（第一个）工具请求
     *
     * <p>用于策略选择判断。当 LLM 并行调用多个工具时，
     * 通常以第一个工具的类型决定整体处理策略。
     *
     * @return 第一个请求，如果没有则返回 null
     */
    public ToolExecutionRequest primaryRequest() {
        return requests != null && !requests.isEmpty() ? requests.get(0) : null;
    }

    /**
     * 创建构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * ToolExecutionContext 构建器。
     *
     * 作用：按需逐项填充执行上下文，避免一次性 new 出一串长参数。
     */
    public static class Builder {
        private QueryLoopState state;
        private OpenVikingIdentity openVikingIdentity;
        private ChatRunTraceContext traceContext;
        private TraceAgentDescriptor agentDescriptor;
        private List<ToolExecutionRequest> requests = Collections.emptyList();
        private List<ToolExecutionResultMessage> blockedResults = Collections.emptyList();
        private List<ToolExecutionRequest> blockedRequests = Collections.emptyList();

        /** 设置当前状态机状态。 */
        public Builder state(QueryLoopState state) {
            this.state = state;
            return this;
        }

        /** 设置当前 OpenViking 身份。 */
        public Builder openVikingIdentity(OpenVikingIdentity openVikingIdentity) {
            this.openVikingIdentity = openVikingIdentity;
            return this;
        }

        /** 设置当前运行的 trace 上下文。 */
        public Builder traceContext(ChatRunTraceContext traceContext) {
            this.traceContext = traceContext;
            return this;
        }

        /** 设置当前执行批次所属的 Agent 描述。 */
        public Builder agentDescriptor(TraceAgentDescriptor agentDescriptor) {
            this.agentDescriptor = agentDescriptor;
            return this;
        }

        /** 设置待执行的工具请求列表。 */
        public Builder requests(List<ToolExecutionRequest> requests) {
            this.requests = requests;
            return this;
        }

        /** 设置已经被 Hook 阻断后生成的结果消息。 */
        public Builder blockedResults(List<ToolExecutionResultMessage> blockedResults) {
            this.blockedResults = blockedResults;
            return this;
        }

        /** 设置已经被 Hook 阻断的原始请求。 */
        public Builder blockedRequests(List<ToolExecutionRequest> blockedRequests) {
            this.blockedRequests = blockedRequests;
            return this;
        }

        /** 构建完整的工具执行上下文对象。 */
        public ToolExecutionContext build() {
            return new ToolExecutionContext(state, openVikingIdentity, traceContext, agentDescriptor, requests, blockedResults, blockedRequests);
        }
    }
}
