package com.msz.resume.ai.chat.runtime.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 工作台产物事件服务。
 *
 * 作用：当 `publishArtifact` 工具真正产出可展示内容后，
 * 把“简历生成好了”“思维导图准备好了”这类用户能感知的结果翻译成时间线事件。
 * 说白了，它就是专门负责“交付物到货提醒”。
 */
@Slf4j
@Service
public class ArtifactActionEventService {

    private final ObjectMapper objectMapper;
    private final TimelineActionService timelineActionService;

    /** 注入 JSON 解析器和时间线服务，用来识别 artifact 类型并发出交付事件。 */
    public ArtifactActionEventService(ObjectMapper objectMapper,
                                      TimelineActionService timelineActionService) {
        this.objectMapper = objectMapper;
        this.timelineActionService = timelineActionService;
    }

    /** 当工具产出 artifact 后发布一条可见事件，让前端知道工作台里有新内容了。 */
    public void artifactReady(ChatRunTraceContext traceContext,
                              TraceAgentDescriptor agentDescriptor,
                              ToolExecutionRequest request,
                              String toolResult) {
        if (traceContext == null || !traceContext.isActive() || request == null || toolResult == null || toolResult.isBlank()) {
            return;
        }
        if (!"publishArtifact".equals(request.name())) {
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(toolResult);
            String type = root.path("type").asText("");
            if (type.isBlank() || "error".equals(type)) {
                return;
            }

            Map<String, Object> payload = timelineActionService
                    .builder("artifact_ready_" + (request.id() != null ? request.id() : Integer.toHexString(System.identityHashCode(request))),
                            traceContext,
                            agentDescriptor)
                    .toolCallId(request.id())
                    .title(titleFor(type))
                    .summary(summaryFor(type))
                    .status("success")
                    .put("artifactType", type)
                    .build();
            timelineActionService.publish(traceContext, "artifact_ready", payload, "ArtifactActionEventService");
        } catch (Exception e) {
            log.warn("[ArtifactActionEventService] artifact_ready send failed: toolCallId={}, error={}",
                    request.id(), e.getMessage());
        }
    }

    /** 根据 artifact 类型生成标题，像给不同交付物贴上合适的标签。 */
    private String titleFor(String type) {
        return switch (type) {
            case "resume" -> "简历已生成";
            case "optimize_result" -> "优化分析已生成";
            case "mindmap" -> "思维导图已生成";
            case "markdown" -> "文档已生成";
            case "questionnaire" -> "问题清单已生成";
            default -> "产物已生成";
        };
    }

    /** 根据 artifact 类型生成一句后续操作提示。 */
    private String summaryFor(String type) {
        return switch (type) {
            case "resume" -> "可在工作台预览、编辑和导出";
            case "optimize_result" -> "可在工作台查看匹配分析和优化建议";
            case "mindmap" -> "可在工作台打开查看结构图";
            case "markdown" -> "可在工作台打开查看内容";
            default -> "可在工作台打开查看";
        };
    }
}
