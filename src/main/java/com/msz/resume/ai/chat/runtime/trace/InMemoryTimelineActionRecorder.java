package com.msz.resume.ai.chat.runtime.trace;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的 timeline action 记录器。
 *
 * 作用：在一次 SSE 运行期间，按 actionId 保存前端需要展示的最新动作状态，
 * 方便最终落库或在流结束时一次性回放给前端。
 * 可以把它理解成“运行中白板”，同一个动作后续有更新，就在原位置覆盖补全。
 *
 * 代码逻辑：
 * 1. 收到原始事件后，先用 TimelineActionPayloadProjector 投影成标准 action
 * 2. 按 actionId 合并同一动作的多次更新，保留首次 sequence 和最新状态
 * 3. snapshot 时按 firstSequence 排序，输出稳定的前端时间线列表
 */
public class InMemoryTimelineActionRecorder implements TimelineActionRecorder {

    private final Map<String, Map<String, Object>> actionsById = new ConcurrentHashMap<>();
    private final TimelineActionPayloadProjector payloadProjector = new TimelineActionPayloadProjector();

    @Override
    /** 记录一条事件，并把同一 actionId 的增量更新合并成最终可展示状态。 */
    public void record(String eventType, long sequence, Map<String, Object> payload) {
        Map<String, Object> action = payloadProjector.project(eventType, sequence, payload).orElse(null);
        if (action == null) {
            return;
        }
        String id = String.valueOf(action.getOrDefault("id", ""));

        actionsById.merge(id, action, (previous, next) -> {
            Map<String, Object> merged = new LinkedHashMap<>(previous);
            merged.putAll(next);
            Object previousFirstSequence = previous.get("firstSequence");
            merged.put("firstSequence", previousFirstSequence != null ? previousFirstSequence : previous.get("sequence"));
            merged.put("sequence", next.get("sequence"));
            merged.put("eventType", eventType);
            merged.put("kind", TimelineActionPayloadProjector.kindFor(eventType));
            return merged;
        });
    }

    @Override
    /** 导出当前内存里的时间线快照，按首次出现顺序稳定排序。 */
    public List<Map<String, Object>> snapshot() {
        return actionsById.values().stream()
                .map(action -> (Map<String, Object>) new LinkedHashMap<>(action))
                .sorted(Comparator
                        .comparingLong(InMemoryTimelineActionRecorder::firstSequence)
                        .thenComparing(action -> String.valueOf(action.getOrDefault("id", ""))))
                .toList();
    }

    /** 取动作首次出现的序号，排序时优先保证时间线先后稳定。 */
    private static long firstSequence(Map<String, Object> action) {
        Object value = action.get("firstSequence");
        if (value == null) {
            value = action.get("sequence");
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return Long.MAX_VALUE;
        }
    }
}
