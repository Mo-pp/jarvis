package com.msz.resume.ai.chat.runtime.trace.stream;

import com.msz.resume.ai.chat.runtime.trace.CompositeTimelineActionRecorder;
import com.msz.resume.ai.chat.runtime.trace.TimelineActionRecorder;
import org.springframework.stereotype.Component;

/**
 * TimelineActionRecorder 工厂。
 *
 * 作用：按当前配置拼装出真正要用的 recorder 组合，
 * 既支持只记录本地内存，也支持“内存 + Redis Trace Stream”双写。
 * 可以把它理解成“录制链路装配器”，根据开关给调用方发一套合适的录制方案。
 *
 * 代码逻辑：
 * 1. 注入主 recorder 和 Trace Stream 配置
 * 2. 关闭 Trace Stream 时直接返回主 recorder
 * 3. 打开 Trace Stream 时返回 CompositeTimelineActionRecorder，做双路写入
 */
@Component
public class TimelineActionRecorderFactory {

    private final TraceStreamPublisher traceStreamPublisher;
    private final TraceStreamProperties properties;

    /** 创建 recorder 工厂，统一决定一次会话该怎么录 timeline。 */
    public TimelineActionRecorderFactory(TraceStreamPublisher traceStreamPublisher,
                                         TraceStreamProperties properties) {
        this.traceStreamPublisher = traceStreamPublisher;
        this.properties = properties;
    }

    /** 为当前 session 组装 recorder；需要时自动附带 Trace Stream 下游。 */
    public TimelineActionRecorder withTraceStream(String sessionId, TimelineActionRecorder primary) {
        if (!properties.isEnabled()) {
            return primary != null ? primary : TimelineActionRecorder.noop();
        }
        return CompositeTimelineActionRecorder.of(
                primary,
                new TraceStreamTimelineActionRecorder(sessionId, traceStreamPublisher)
        );
    }
}
