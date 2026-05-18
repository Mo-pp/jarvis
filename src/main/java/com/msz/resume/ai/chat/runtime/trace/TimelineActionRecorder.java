package com.msz.resume.ai.chat.runtime.trace;

import java.util.List;
import java.util.Map;

/**
 * 对话时间线动作记录器。
 *
 * 作用：采集那些最终要给用户回放的可见动作事件，
 * 比如工具开始、工具结果、提问挂起、子 Agent 委托等。
 * 可以把它理解成“聊天回放摄像机”，专门记录用户看得见的轨迹。
 */
public interface TimelineActionRecorder {

    /** 记录一条时间线动作，供当前轮结束后持久化或回放。 */
    void record(String eventType, long sequence, Map<String, Object> payload);

    /** 导出当前已录下的动作快照，给持久化层或历史回放使用。 */
    List<Map<String, Object>> snapshot();

    /** 返回空实现，适合那些只发 SSE、不需要保存回放数据的场景。 */
    static TimelineActionRecorder noop() {
        return new TimelineActionRecorder() {
            @Override
            /** 空实现：忽略所有记录请求。 */
            public void record(String eventType, long sequence, Map<String, Object> payload) {
                // no-op
            }

            @Override
            /** 空实现：永远返回空快照。 */
            public List<Map<String, Object>> snapshot() {
                return List.of();
            }
        };
    }
}
