package com.msz.resume.ai.chat.runtime.trace.stream;

import com.msz.resume.ai.chat.runtime.trace.TimelineActionRecorder;

import java.util.List;
import java.util.Map;

/**
 * 面向 Trace Stream 的 timeline recorder。
 *
 * 作用：把 recorder 接口收到的 timeline 事件直接转发给 TraceStreamPublisher，
 * 让上游仍按 TimelineActionRecorder 编程，下游却能无缝写入 Redis Stream。
 * 可以把它理解成“接口转接头”，左边接 recorder，右边接 stream publisher。
 */
public class TraceStreamTimelineActionRecorder implements TimelineActionRecorder {

    private final String sessionId;
    private final TraceStreamPublisher publisher;

    /** 创建一个面向指定 session 的 Trace Stream recorder。 */
    public TraceStreamTimelineActionRecorder(String sessionId, TraceStreamPublisher publisher) {
        this.sessionId = sessionId;
        this.publisher = publisher;
    }

    @Override
    /** 把 timeline 事件转发到 Trace Stream。 */
    public void record(String eventType, long sequence, Map<String, Object> payload) {
        publisher.publishTimelineEvent(sessionId, eventType, sequence, payload);
    }

    @Override
    /** 这个 recorder 只负责转发，不维护本地快照。 */
    public List<Map<String, Object>> snapshot() {
        return List.of();
    }
}
