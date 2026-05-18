package com.msz.resume.ai.chat.runtime.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 时间线动作多播记录器。
 *
 * 作用：把同一条 timeline action 同时写到多个下游记录器里，
 * 比如一份给当前 SSE 会话内存快照，一份给 Redis Trace Stream。
 * 可以把它理解成“分线器”，一条事件进来，多路一起往下发。
 *
 * 代码逻辑：
 * 1. 构造时过滤掉空 recorder，避免后续广播时判空
 * 2. record 时把同一条事件逐个转发给所有 delegate
 * 3. snapshot 时把所有 delegate 的快照合并成一个列表返回
 */
public class CompositeTimelineActionRecorder implements TimelineActionRecorder {

    private final List<TimelineActionRecorder> delegates;

    /** 创建组合记录器，把多个下游 recorder 串成一个广播出口。 */
    public CompositeTimelineActionRecorder(List<TimelineActionRecorder> delegates) {
        this.delegates = delegates != null
                ? delegates.stream().filter(java.util.Objects::nonNull).toList()
                : List.of();
    }

    /** 快速创建组合 recorder；如果一个都没有，就退回 noop。 */
    public static TimelineActionRecorder of(TimelineActionRecorder... delegates) {
        if (delegates == null || delegates.length == 0) {
            return TimelineActionRecorder.noop();
        }
        return new CompositeTimelineActionRecorder(List.of(delegates));
    }

    @Override
    /** 把一条 timeline 事件广播给所有下游记录器。 */
    public void record(String eventType, long sequence, Map<String, Object> payload) {
        for (TimelineActionRecorder delegate : delegates) {
            delegate.record(eventType, sequence, payload);
        }
    }

    @Override
    /** 汇总所有下游 recorder 的快照结果。 */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (TimelineActionRecorder delegate : delegates) {
            snapshots.addAll(delegate.snapshot());
        }
        return snapshots;
    }
}
