package com.msz.resume.ai.chat.runtime.trace.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jarvis.trace.stream")
/**
 * Trace Stream 配置项。
 *
 * 作用：集中承载 Redis Trace Stream 的开关、流名、消费组、批量大小、重试和死信策略。
 * 可以把它理解成“Trace 流水线控制面板”，链路怎么跑、跑多快、失败怎么兜底都从这里拿。
 */
public class TraceStreamProperties {

    private boolean enabled = true;
    private boolean consumerEnabled = true;
    private String streamKey = "jarvis:trace:stream";
    private String group = "trace-db-writer";
    private String consumerName = "jarvis-app";
    private int batchSize = 50;
    private long blockTimeoutMs = 1000;
    private long pollIntervalMs = 1000;
    private long pendingRetryMs = 30000;
    private int pendingRecoveryBatchSize = 20;
    private long claimIdleMs = 30000;
    private long maxDeliveryCount = 5;
    private boolean deadLetterEnabled = true;
    private String deadLetterStreamKey = "jarvis:trace:stream:dead-letter";
    private long maxLen = 10000;
    private boolean approximateTrim = true;

    /** 读取 Trace Stream 总开关。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 设置 Trace Stream 总开关。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 读取消费者开关。 */
    public boolean isConsumerEnabled() {
        return consumerEnabled;
    }

    /** 设置消费者开关。 */
    public void setConsumerEnabled(boolean consumerEnabled) {
        this.consumerEnabled = consumerEnabled;
    }

    /** 读取 Redis Stream key。 */
    public String getStreamKey() {
        return streamKey;
    }

    /** 设置 Redis Stream key。 */
    public void setStreamKey(String streamKey) {
        this.streamKey = streamKey;
    }

    /** 读取消费组名称。 */
    public String getGroup() {
        return group;
    }

    /** 设置消费组名称。 */
    public void setGroup(String group) {
        this.group = group;
    }

    /** 读取当前消费者名称。 */
    public String getConsumerName() {
        return consumerName;
    }

    /** 设置当前消费者名称。 */
    public void setConsumerName(String consumerName) {
        this.consumerName = consumerName;
    }

    /** 读取单批拉取大小。 */
    public int getBatchSize() {
        return batchSize;
    }

    /** 设置单批拉取大小。 */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    /** 读取阻塞读取超时时间。 */
    public long getBlockTimeoutMs() {
        return blockTimeoutMs;
    }

    /** 设置阻塞读取超时时间。 */
    public void setBlockTimeoutMs(long blockTimeoutMs) {
        this.blockTimeoutMs = blockTimeoutMs;
    }

    /** 读取定时轮询间隔。 */
    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    /** 设置定时轮询间隔。 */
    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    /** 读取 pending 恢复重试间隔。 */
    public long getPendingRetryMs() {
        return pendingRetryMs;
    }

    /** 设置 pending 恢复重试间隔。 */
    public void setPendingRetryMs(long pendingRetryMs) {
        this.pendingRetryMs = pendingRetryMs;
    }

    /** 读取单次 pending 恢复最多扫描多少条。 */
    public int getPendingRecoveryBatchSize() {
        return pendingRecoveryBatchSize;
    }

    /** 设置单次 pending 恢复最多扫描多少条。 */
    public void setPendingRecoveryBatchSize(int pendingRecoveryBatchSize) {
        this.pendingRecoveryBatchSize = pendingRecoveryBatchSize;
    }

    /** 读取 claim 所需的最小空闲时间。 */
    public long getClaimIdleMs() {
        return claimIdleMs;
    }

    /** 设置 claim 所需的最小空闲时间。 */
    public void setClaimIdleMs(long claimIdleMs) {
        this.claimIdleMs = claimIdleMs;
    }

    /** 读取最大投递次数。 */
    public long getMaxDeliveryCount() {
        return maxDeliveryCount;
    }

    /** 设置最大投递次数。 */
    public void setMaxDeliveryCount(long maxDeliveryCount) {
        this.maxDeliveryCount = maxDeliveryCount;
    }

    /** 读取死信开关。 */
    public boolean isDeadLetterEnabled() {
        return deadLetterEnabled;
    }

    /** 设置死信开关。 */
    public void setDeadLetterEnabled(boolean deadLetterEnabled) {
        this.deadLetterEnabled = deadLetterEnabled;
    }

    /** 读取死信流 key。 */
    public String getDeadLetterStreamKey() {
        return deadLetterStreamKey;
    }

    /** 设置死信流 key。 */
    public void setDeadLetterStreamKey(String deadLetterStreamKey) {
        this.deadLetterStreamKey = deadLetterStreamKey;
    }

    /** 读取 Stream 最大长度。 */
    public long getMaxLen() {
        return maxLen;
    }

    /** 设置 Stream 最大长度。 */
    public void setMaxLen(long maxLen) {
        this.maxLen = maxLen;
    }

    /** 读取是否启用近似裁剪。 */
    public boolean isApproximateTrim() {
        return approximateTrim;
    }

    /** 设置是否启用近似裁剪。 */
    public void setApproximateTrim(boolean approximateTrim) {
        this.approximateTrim = approximateTrim;
    }
}
