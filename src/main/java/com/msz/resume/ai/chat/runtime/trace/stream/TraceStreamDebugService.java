package com.msz.resume.ai.chat.runtime.trace.stream;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trace Stream 调试服务。
 *
 * 作用：集中输出 Redis Trace Stream 的运行状态、最近事件、消费者积压和指标快照，
 * 方便开发期或线上排障时快速看清链路健康度。
 * 可以把它理解成“监控仪表盘后端”，把散落在 Redis 里的状态拼成一页可读信息。
 *
 * 代码逻辑：
 * 1. 汇总配置、stream info、group info、consumer info、pending 概况
 * 2. 读取消费者指标快照
 * 3. 支持查看最近正常事件和最近死信事件
 * 4. 所有 Redis 查询都做兜底，避免调试接口把主流程拖挂
 */
@Service
public class TraceStreamDebugService {

    private final StringRedisTemplate redisTemplate;
    private final TraceStreamProperties properties;
    private final TraceStreamMetrics metrics;

    /** 创建调试服务，统一查询 Trace Stream 的运行状态。 */
    public TraceStreamDebugService(StringRedisTemplate redisTemplate,
                                   TraceStreamProperties properties,
                                   TraceStreamMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** 返回当前 Trace Stream 的综合状态快照。 */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", properties.isEnabled());
        status.put("consumerEnabled", properties.isConsumerEnabled());
        status.put("streamKey", properties.getStreamKey());
        status.put("group", properties.getGroup());
        status.put("consumerName", properties.getConsumerName());
        status.put("batchSize", properties.getBatchSize());
        status.put("blockTimeoutMs", properties.getBlockTimeoutMs());
        status.put("pollIntervalMs", properties.getPollIntervalMs());
        status.put("pendingRetryMs", properties.getPendingRetryMs());
        status.put("pendingRecoveryBatchSize", properties.getPendingRecoveryBatchSize());
        status.put("claimIdleMs", properties.getClaimIdleMs());
        status.put("maxDeliveryCount", properties.getMaxDeliveryCount());
        status.put("deadLetterEnabled", properties.isDeadLetterEnabled());
        status.put("deadLetterStreamKey", properties.getDeadLetterStreamKey());
        status.put("maxLen", properties.getMaxLen());
        status.put("approximateTrim", properties.isApproximateTrim());

        status.put("streamInfo", streamInfo(properties.getStreamKey()));
        status.put("groupInfo", groupInfo(properties.getStreamKey(), properties.getGroup()));
        status.put("consumerInfo", consumerInfo(properties.getStreamKey(), properties.getGroup()));
        status.put("pendingSummary", pendingSummary(properties.getStreamKey(), properties.getGroup()));
        status.put("backlogEstimate", backlogEstimate(properties.getStreamKey(), properties.getGroup()));
        status.put("metrics", metrics.snapshot());
        status.put("deadLetterStreamInfo", streamInfo(properties.getDeadLetterStreamKey()));
        return status;
    }

    /** 查看最近的正常 Trace Stream 事件。 */
    public List<Map<String, Object>> recentEvents(int count) {
        return recentRecords(properties.getStreamKey(), count);
    }

    /** 查看最近进入死信流的事件。 */
    public List<Map<String, Object>> recentDeadLetters(int count) {
        return recentRecords(properties.getDeadLetterStreamKey(), count);
    }

    /** 查询某个 stream 的基础信息，比如长度和首尾消息。 */
    private Map<String, Object> streamInfo(String streamKey) {
        try {
            StreamInfo.XInfoStream info = redisTemplate.opsForStream().info(streamKey);
            if (info == null) {
                return Map.of("exists", false);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exists", true);
            result.put("length", info.streamLength());
            result.put("groupCount", info.groupCount());
            result.put("lastGeneratedId", info.lastGeneratedId());
            result.put("firstEntryId", info.firstEntryId());
            result.put("lastEntryId", info.lastEntryId());
            return result;
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exists", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /** 查询指定消费组的组级状态。 */
    private List<Map<String, Object>> groupInfo(String streamKey, String group) {
        try {
            StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(streamKey);
            return groups.stream()
                    .filter(item -> group.equals(item.groupName()))
                    .map(item -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("groupName", item.groupName());
                        result.put("consumerCount", item.consumerCount());
                        result.put("pendingCount", item.pendingCount());
                        result.put("lastDeliveredId", item.lastDeliveredId());
                        return result;
                    })
                    .toList();
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("error", e.getMessage());
            return List.of(result);
        }
    }

    /** 查询指定消费组下各个 consumer 的状态。 */
    private List<Map<String, Object>> consumerInfo(String streamKey, String group) {
        try {
            StreamInfo.XInfoConsumers consumers = redisTemplate.opsForStream().consumers(streamKey, group);
            return consumers.stream()
                    .map(item -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("groupName", item.groupName());
                        result.put("consumerName", item.consumerName());
                        result.put("idleTimeMs", item.idleTimeMs());
                        result.put("pendingCount", item.pendingCount());
                        return result;
                    })
                    .toList();
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("error", e.getMessage());
            return List.of(result);
        }
    }

    /** 汇总某个消费组当前 pending 消息概况。 */
    private Map<String, Object> pendingSummary(String streamKey, String group) {
        try {
            PendingMessagesSummary summary = redisTemplate.opsForStream().pending(streamKey, group);
            if (summary == null) {
                return Map.of("exists", false);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exists", true);
            result.put("groupName", summary.getGroupName());
            result.put("totalPendingMessages", summary.getTotalPendingMessages());
            result.put("minMessageId", summary.minMessageId());
            result.put("maxMessageId", summary.maxMessageId());
            result.put("pendingMessagesPerConsumer", summary.getPendingMessagesPerConsumer());
            return result;
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exists", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /** 粗略估算消费组积压情况，帮助判断有没有明显堆积。 */
    private Map<String, Object> backlogEstimate(String streamKey, String group) {
        try {
            StreamInfo.XInfoStream stream = redisTemplate.opsForStream().info(streamKey);
            StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(streamKey);
            if (stream == null || groups == null) {
                return Map.of("exists", false);
            }
            return groups.stream()
                    .filter(item -> group.equals(item.groupName()))
                    .findFirst()
                    .map(item -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("exists", true);
                        result.put("streamLength", stream.streamLength());
                        result.put("lastGeneratedId", stream.lastGeneratedId());
                        result.put("lastDeliveredId", item.lastDeliveredId());
                        result.put("pendingCount", item.pendingCount());
                        result.put("lagAvailable", false);
                        return result;
                    })
                    .orElseGet(() -> Map.of("exists", false));
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exists", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /** 读取某个 stream 最近几条原始记录，便于直接排查字段内容。 */
    private List<Map<String, Object>> recentRecords(String streamKey, int count) {
        try {
            var records = redisTemplate.opsForStream().reverseRange(
                    streamKey,
                    Range.unbounded(),
                    Limit.limit().count(Math.max(1, count))
            );
            return records.stream()
                    .map(record -> {
                        @SuppressWarnings("unchecked")
                        MapRecord<String, Object, Object> typedRecord = (MapRecord<String, Object, Object>) record;
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", typedRecord.getId().getValue());
                        item.put("stream", streamKey);
                        item.put("fields", new LinkedHashMap<>(typedRecord.getValue()));
                        return item;
                    })
                    .toList();
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("error", e.getMessage());
            return List.of(result);
        }
    }
}
