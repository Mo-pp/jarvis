package com.msz.resume.ai.chat.runtime.trace.stream;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trace Stream 指标累加器。
 *
 * 作用：记录消费者轮询失败、持久化失败、死信数量以及最近批次吞吐，
 * 给调试接口和排障页面提供一眼能看的运行指标。
 * 可以把它理解成“记分牌”，每消费一批就把这次成绩和累计成绩记下来。
 *
 * 代码逻辑：
 * 1. 用 AtomicLong 记录各类累计计数
 * 2. 每批消费完成后刷新最近一批的读取量、入库量、耗时和延迟
 * 3. snapshot 时导出当前完整指标快照
 */
@Component
public class TraceStreamMetrics {

    private final AtomicLong pollFailureCount = new AtomicLong();
    private final AtomicLong persistenceFailureCount = new AtomicLong();
    private final AtomicLong invalidEventCount = new AtomicLong();
    private final AtomicLong pendingRecoveryFailureCount = new AtomicLong();
    private final AtomicLong deadLetterCount = new AtomicLong();
    private final AtomicLong totalRead = new AtomicLong();
    private final AtomicLong totalPersisted = new AtomicLong();
    private final AtomicLong totalAcked = new AtomicLong();
    private final AtomicLong lastBatchRead = new AtomicLong();
    private final AtomicLong lastBatchPersisted = new AtomicLong();
    private final AtomicLong lastBatchAcked = new AtomicLong();
    private final AtomicLong lastBatchDurationMs = new AtomicLong();
    private final AtomicLong lastBatchMaxEventLagMs = new AtomicLong();
    private final AtomicLong lastConsumedAtEpochMs = new AtomicLong();

    /** 记录一次轮询失败。 */
    public void recordPollFailure() {
        pollFailureCount.incrementAndGet();
    }

    /** 记录一次入库失败。 */
    public void recordPersistenceFailure() {
        persistenceFailureCount.incrementAndGet();
    }

    /** 记录一次非法事件。 */
    public void recordInvalidEvent() {
        invalidEventCount.incrementAndGet();
    }

    /** 记录一次 pending 恢复失败。 */
    public void recordPendingRecoveryFailure() {
        pendingRecoveryFailureCount.incrementAndGet();
    }

    /** 记录一次死信转移。 */
    public void recordDeadLetter() {
        deadLetterCount.incrementAndGet();
    }

    /** 记录一批消费的吞吐和耗时指标。 */
    public void recordBatch(int read, int persisted, int acked, long durationMs, long maxEventLagMs) {
        totalRead.addAndGet(Math.max(0, read));
        totalPersisted.addAndGet(Math.max(0, persisted));
        totalAcked.addAndGet(Math.max(0, acked));
        lastBatchRead.set(Math.max(0, read));
        lastBatchPersisted.set(Math.max(0, persisted));
        lastBatchAcked.set(Math.max(0, acked));
        lastBatchDurationMs.set(Math.max(0L, durationMs));
        lastBatchMaxEventLagMs.set(Math.max(0L, maxEventLagMs));
        lastConsumedAtEpochMs.set(System.currentTimeMillis());
    }

    /** 导出当前所有指标快照，给调试接口直接展示。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalRead", totalRead.get());
        metrics.put("totalPersisted", totalPersisted.get());
        metrics.put("totalAcked", totalAcked.get());
        metrics.put("lastBatchRead", lastBatchRead.get());
        metrics.put("lastBatchPersisted", lastBatchPersisted.get());
        metrics.put("lastBatchAcked", lastBatchAcked.get());
        metrics.put("lastBatchDurationMs", lastBatchDurationMs.get());
        metrics.put("lastBatchMaxEventLagMs", lastBatchMaxEventLagMs.get());
        metrics.put("lastConsumedAt", lastConsumedAt());
        metrics.put("pollFailureCount", pollFailureCount.get());
        metrics.put("persistenceFailureCount", persistenceFailureCount.get());
        metrics.put("invalidEventCount", invalidEventCount.get());
        metrics.put("pendingRecoveryFailureCount", pendingRecoveryFailureCount.get());
        metrics.put("deadLetterCount", deadLetterCount.get());
        return metrics;
    }

    /** 取最近一次成功消费的时间。 */
    private String lastConsumedAt() {
        long epochMs = lastConsumedAtEpochMs.get();
        return epochMs > 0 ? Instant.ofEpochMilli(epochMs).toString() : "";
    }
}
