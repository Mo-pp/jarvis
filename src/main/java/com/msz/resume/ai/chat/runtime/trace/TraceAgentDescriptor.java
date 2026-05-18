package com.msz.resume.ai.chat.runtime.trace;

/**
 * 运行时 Agent 的展示描述。
 *
 * 作用：统一描述一个 Trace 节点属于哪个 Agent、显示什么名字、处于主链还是子链。
 * 前端展示步骤树时，会用它来区分 Main Agent 和各个子 Agent。
 */
public record TraceAgentDescriptor(
        String agentId,
        String agentScope,
        String agentLabel,
        String subAgentType
) {

    /** 返回主 Agent 的默认描述，等于给主链路贴上固定的身份标签。 */
    public static TraceAgentDescriptor mainAgent() {
        return new TraceAgentDescriptor("main", "main", "Main Agent", null);
    }
}
