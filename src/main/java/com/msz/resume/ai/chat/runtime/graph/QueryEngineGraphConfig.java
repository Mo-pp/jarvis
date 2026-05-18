package com.msz.resume.ai.chat.runtime.graph;


import com.msz.resume.ai.integrations.openviking.core.context.OpenVikingIdentitySupport;
import com.msz.resume.ai.integrations.openviking.core.model.OpenVikingIdentity;
import com.msz.resume.ai.chat.runtime.node.outer.SessionInitNode;
import com.msz.resume.ai.chat.runtime.node.outer.UsageStatNode;
import com.msz.resume.ai.chat.runtime.state.serialization.SessionStateSerializer;
import com.msz.resume.ai.chat.runtime.state.QueryLoopState;
import com.msz.resume.ai.chat.runtime.state.SessionState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * 外层会话图配置。
 *
 * 作用：定义一次用户请求在外层 SessionState 里的完整流转方式，
 * 同时负责把内层 QueryLoopState 跑起来，并把 trace 相关字段原样透传进去。
 * 可以把它理解成“总流程骨架”，外层管会话生命周期，内层管真正的思考和工具循环。
 *
 * 代码逻辑：
 * 1. 定义 session_init → run_inner_loop → usage_stat → END 的单轮链路
 * 2. 在 run_inner_loop 节点里把外层状态拆成内层输入
 * 3. 把 traceRunId / traceAgentId / traceAgentLabel / traceAgentScope 一起传入内层图
 * 4. 等内层图跑完后，把最终 innerState 再塞回 SessionState
 */
@Slf4j
@Configuration
public class QueryEngineGraphConfig {

    private static final int INNER_LOOP_RECURSION_LIMIT = 100;

    @Bean
    /** 编译外层会话图，并把内层 Query Loop 挂进其中一个节点里执行。 */
    public CompiledGraph<SessionState> queryEngineGraph(
            SessionInitNode sessionInitNode,
            UsageStatNode usageStatNode,
            StateGraph<QueryLoopState> queryLoopGraph
    )throws Exception{
        AsyncNodeAction<SessionState> runInnerLoop =new AsyncNodeAction<SessionState>() {

            @Override
            public CompletableFuture<Map<String, Object>> apply(SessionState sessionState) {
                OpenVikingIdentity identity = OpenVikingIdentitySupport.fromSessionState(sessionState);
                return OpenVikingIdentitySupport.supplyAsync(identity, () -> {
                    QueryLoopState innerState = sessionState.<QueryLoopState>value(SessionState.INNER_STATE).orElseThrow();

                    Map<String, Object> innerInput = new HashMap<>();
                    innerInput.put(QueryLoopState.MESSAGE_HISTORY, innerState.getMessages());
                    innerInput.put(QueryLoopState.USER_CONTEXT, sessionState.getUserContext());
                    innerInput.put(QueryLoopState.OPENVIKING_IDENTITY, identity);
                    innerInput.put(QueryLoopState.SESSION_ID, sessionState.getSessionId());
                    innerInput.put(QueryLoopState.TASK_PLAN, innerState.getTaskPlan());
                    innerInput.put(QueryLoopState.SURFACED_OPENVIKING_URIS, innerState.getSurfacedOpenVikingUris());
                    innerInput.put(QueryLoopState.TRACE_RUN_ID, innerState.getTraceRunId());
                    innerInput.put(QueryLoopState.TRACE_AGENT_ID, innerState.getTraceAgentId());
                    innerInput.put(QueryLoopState.TRACE_AGENT_LABEL, innerState.getTraceAgentLabel());
                    innerInput.put(QueryLoopState.TRACE_AGENT_SCOPE, innerState.getTraceAgentScope());

                    CompiledGraph<QueryLoopState> compiledInner = null;
                    try {
                        CompileConfig innerCompileConfig = CompileConfig.builder()
                                .recursionLimit(INNER_LOOP_RECURSION_LIMIT)
                                .build();
                        compiledInner = queryLoopGraph.compile(innerCompileConfig);

                        QueryLoopState finalInnerState = null;
                        for (var output : compiledInner.stream(innerInput)) {
                            log.info("[内层步骤] " + output.node() + ", 状态: " + output.state());
                            finalInnerState = output.state();
                        }
                        Map<String, Object> update = new HashMap<>();
                        update.put(SessionState.INNER_STATE, finalInnerState);
                        update.put(SessionState.OPENVIKING_IDENTITY, identity);
                        return update;

                    } catch (GraphStateException e) {
                        log.error("[内层循环执行失败] 会话ID: {}", sessionState.getSessionId(), e);
                        throw new RuntimeException("内层循环编译失败", e);
                    }
                });
            }
        };

        StateGraph<SessionState> workflow = new StateGraph<>(SessionState.SCHEMA, new SessionStateSerializer());

        workflow.addNode("session_init", sessionInitNode);
        workflow.addNode("run_inner_loop", runInnerLoop);
        workflow.addNode("usage_stat", usageStatNode);

        workflow.addEdge(START, "session_init");
        workflow.addEdge("session_init", "run_inner_loop");
        workflow.addEdge("run_inner_loop", "usage_stat");
        workflow.addEdge("usage_stat", END);

        CompileConfig config = CompileConfig.builder()
                .build();

        return workflow.compile(config);
    }
}
