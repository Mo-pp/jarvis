package com.msz.resume.ai.chat.runtime.trace;

/**
 * Trace 步骤事件发布器接口。
 *
 * 作用：把“步骤树里的节点变化”抽象成统一发布口，
 * 这样 TraceService 不必关心事件最终是发到 SSE、测试桩，还是别的输出端。
 */
public interface TracePublisher {

    /** 发布一个步骤事件，相当于把某个节点状态变化广播出去。 */
    void publishStep(StepTraceEvent event);

    /** 返回一个空实现，适合测试或不需要真实输出的场景兜底。 */
    static TracePublisher noop() {
        return event -> {
        };
    }
}
