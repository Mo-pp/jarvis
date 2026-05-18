package com.msz.resume.ai.chat.runtime.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具动作事件服务。
 *
 * 作用：把底层工具调用过程翻译成用户可见的时间线动作，
 * 包括开始、进度、成功、失败、阻断，以及工具参数预览、资源范围、分组信息等。
 * 它相当于 Trace 和用户之间的“同传翻译”，把技术执行细节压成可读卡片。
 *
 * 代码逻辑：
 * 1. 对外暴露 started/progress/succeeded/failed/blocked 五类事件入口
 * 2. 统一为每个工具生成标题、描述、预览和分组信息
 * 3. 对工具结果做摘要归纳，避免把原始大段文本直接砸给时间线
 * 4. 只保留安全预览字段，避免敏感参数泄露到前端
 */
@Slf4j
@Service
public class ToolActionEventService {

    private static final Set<String> OPEN_VIKING_TOOLS = Set.of(
            "openviking_list",
            "openviking_tree",
            "openviking_read",
            "openviking_glob",
            "openviking_grep",
            "openviking_find",
            "openviking_search",
            "openviking_forget",
            "openviking_skill_search",
            "openviking_skill_read",
            "openviking_skill_files",
            "openviking_skill_read_file",
            "openviking_skill_add"
    );

    private static final Set<String> SAFE_PREVIEW_KEYS = Set.of(
            "uri", "targetUri", "query", "pattern", "level", "limit", "nodeLimit", "caseInsensitive",
            "toolName", "tasksJson", "taskId", "newStatus", "description", "detail", "type", "title",
            "artifactType", "name", "path", "maxTurns", "subagentType", "allowedTools", "memoryKey", "preferenceKey"
    );

    private static final int MAX_PREVIEW_VALUE_CHARS = 160;
    private static final int MAX_SUMMARY_CHARS = 220;

    private final ObjectMapper objectMapper;
    private final TimelineActionService timelineActionService;

    /** 注入 JSON 解析器和时间线服务，负责把原始工具调用翻成可见动作卡片。 */
    public ToolActionEventService(ObjectMapper objectMapper,
                                  TimelineActionService timelineActionService) {
        this.objectMapper = objectMapper;
        this.timelineActionService = timelineActionService;
    }

    /** 发布工具开始事件，告诉用户这把工具已经真正开始跑了。 */
    public void toolStarted(ChatRunTraceContext traceContext,
                            TraceAgentDescriptor agentDescriptor,
                            ToolExecutionRequest request) {
        if (!shouldPublish(traceContext, request)) {
            return;
        }
        publish(traceContext, "tool_use_started", buildPayload(traceContext, agentDescriptor, request, "running", null, null));
    }

    /** 发布工具过程摘要，适合展示“正在读目录”“正在检索知识”这类过程提示。 */
    public void toolProgress(ChatRunTraceContext traceContext,
                             TraceAgentDescriptor agentDescriptor,
                             ToolExecutionRequest request,
                             String summary) {
        if (!shouldPublish(traceContext, request) || summary == null || summary.isBlank()) {
            return;
        }
        publish(traceContext, "tool_use_delta", buildPayload(
                traceContext,
                agentDescriptor,
                request,
                "running",
                truncate(summary, MAX_SUMMARY_CHARS),
                null
        ));
    }

    /** 发布工具成功事件，并对原始结果做一层面向用户的摘要压缩。 */
    public void toolSucceeded(ChatRunTraceContext traceContext,
                              TraceAgentDescriptor agentDescriptor,
                              ToolExecutionRequest request,
                              String result) {
        if (!shouldPublish(traceContext, request)) {
            return;
        }
        publish(traceContext, "tool_use_result", buildPayload(traceContext, agentDescriptor, request, "success", summarizeResult(request.name(), result), null));
    }

    /** 发布工具失败事件，把错误裁成适合前端展示的一句摘要。 */
    public void toolFailed(ChatRunTraceContext traceContext,
                           TraceAgentDescriptor agentDescriptor,
                           ToolExecutionRequest request,
                           String error) {
        if (!shouldPublish(traceContext, request)) {
            return;
        }
        publish(traceContext, "tool_use_error", buildPayload(traceContext, agentDescriptor, request, "failed", null, summarizeError(error)));
    }

    /** 发布工具被安全规则阻断的事件，让用户知道不是卡住，而是被拦下来了。 */
    public void toolBlocked(ChatRunTraceContext traceContext,
                            TraceAgentDescriptor agentDescriptor,
                            ToolExecutionRequest request,
                            String reason) {
        if (!shouldPublish(traceContext, request)) {
            return;
        }
        String summary = summarizeBlocked(reason);
        publish(traceContext, "tool_use_error", buildPayload(traceContext, agentDescriptor, request, "blocked", summary, summary));
    }

    /** 测试专用入口：构造预览 payload，不依赖真实 SSE 上下文。 */
    Map<String, Object> previewPayloadForTest(ToolExecutionRequest request, String status, String summary, String error) {
        return buildPayload(
                new ChatRunTraceContext("test-run", "test-session", TracePublisher.noop()),
                TraceAgentDescriptor.mainAgent(),
                request,
                status,
                summary,
                error
        );
    }

    /** 判断当前工具事件是否值得向用户发布。 */
    private boolean shouldPublish(ChatRunTraceContext traceContext, ToolExecutionRequest request) {
        return traceContext != null
                && traceContext.isActive()
                && request != null;
    }

    /** 拼装工具动作 payload，把标题、摘要、分组、资源范围这些用户视角字段一次性补全。 */
    private Map<String, Object> buildPayload(ChatRunTraceContext traceContext,
                                             TraceAgentDescriptor agentDescriptor,
                                             ToolExecutionRequest request,
                                             String status,
                                             String summary,
                                             String error) {
        Map<String, Object> preview = safePreview(request.arguments());
        return timelineActionService.builder(actionId(request), traceContext, agentDescriptor)
                .toolCallId(request.id())
                .title(titleFor(request.name()))
                .status(status)
                .summary(summary)
                .error(error)
                .put("toolName", request.name())
                .put("description", descriptionFor(request))
                .put("preview", preview)
                .put("resourceUris", resourceUrisFor(request.name(), preview))
                .put("groupId", groupId(traceContext, agentDescriptor, request, preview))
                .put("groupKind", groupKind(request.name()))
                .put("groupTitle", groupTitle(request.name()))
                .put("groupSummary", groupSummary(request.name(), status))
                .put("foldable", true)
                .build();
    }

    /** 发布工具时间线事件，统一委托给 TimelineActionService。 */
    private void publish(ChatRunTraceContext traceContext, String type, Map<String, Object> payload) {
        timelineActionService.publish(traceContext, type, payload, "ToolActionEventService");
    }

    /** 生成工具动作 ID，让同一工具调用的多次状态变化能合并到一张卡片上。 */
    private String actionId(ToolExecutionRequest request) {
        String toolCallId = request.id();
        if (toolCallId != null && !toolCallId.isBlank()) {
            return "tool_action_" + toolCallId;
        }
        return "tool_action_" + request.name() + "_" + Integer.toHexString(System.identityHashCode(request));
    }

    /** 把工具名翻译成前端可展示的中文标题。 */
    private String titleFor(String toolName) {
        return switch (toolName) {
            case "openviking_list" -> "列出目录";
            case "openviking_tree" -> "浏览目录树";
            case "openviking_read" -> "读取资源";
            case "openviking_glob" -> "按路径模式查找";
            case "openviking_grep" -> "按内容搜索";
            case "openviking_find" -> "检索知识";
            case "openviking_search" -> "结合会话检索";
            case "openviking_forget" -> "删除资源";
            case "toolSearch" -> "查找可用工具";
            case "getCurrentTime" -> "获取当前时间";
            case "createPlan" -> "创建执行计划";
            case "updateStatus" -> "更新任务状态";
            case "addTask" -> "追加任务";
            case "removeTask" -> "移除任务";
            case "publishArtifact" -> "发布工作台产物";
            case "generateMindmap" -> "生成思维导图";
            case "getResumeGuide" -> "获取简历生成指南";
            case "getOptimizeGuide" -> "获取简历优化指南";
            case "openviking_skill_search" -> "检索 Skill";
            case "openviking_skill_read" -> "读取 Skill";
            case "openviking_skill_files" -> "查看 Skill 文件";
            case "openviking_skill_read_file" -> "读取 Skill 文件";
            case "openviking_skill_add" -> "写入 Skill";
            case "rememberUserPreference" -> "记录用户偏好";
            case "rememberUserMemory" -> "记录用户记忆";
            case "readUserMemory" -> "读取用户记忆";
            case "readUserMemoryDetail" -> "读取记忆详情";
            default -> toolName;
        };
    }

    /** 生成工具分组 ID，把同一轮里相近的工具动作收拢成一组。 */
    private String groupId(ChatRunTraceContext traceContext,
                           TraceAgentDescriptor agentDescriptor,
                           ToolExecutionRequest request,
                           Map<String, Object> preview) {
        String agentId = agentDescriptor != null && agentDescriptor.agentId() != null
                ? agentDescriptor.agentId()
                : "main";
        return "tool_group_" + traceContext.runId() + "_" + agentId + "_" + groupKind(request.name()) + "_" + scopeKey(request.name(), preview);
    }

    /** 根据工具类型归类分组种类，方便前端折叠“浏览”“检索”“读文件”等大类。 */
    private String groupKind(String toolName) {
        return switch (toolName) {
            case "openviking_read" -> "openviking_read";
            case "openviking_list", "openviking_tree" -> "openviking_browse";
            case "openviking_glob", "openviking_grep" -> "openviking_match";
            case "openviking_find", "openviking_search" -> "openviking_search";
            case "openviking_forget" -> "openviking_forget";
            case "createPlan", "updateStatus", "addTask", "removeTask" -> "task_plan";
            case "getResumeGuide", "getOptimizeGuide", "publishArtifact" -> "artifact_work";
            case "toolSearch" -> "tool_discovery";
            case "rememberUserPreference", "rememberUserMemory", "readUserMemory", "readUserMemoryDetail" -> "user_memory";
            case "openviking_skill_search", "openviking_skill_read", "openviking_skill_files",
                 "openviking_skill_read_file", "openviking_skill_add" -> "skill";
            default -> toolName;
        };
    }

    /** 给分组生成用户可读标题，相当于折叠面板的大标题。 */
    private String groupTitle(String toolName) {
        return switch (groupKind(toolName)) {
            case "openviking_read" -> "读取关键资源";
            case "openviking_browse" -> "浏览资源结构";
            case "openviking_match" -> "定位候选资源";
            case "openviking_search" -> "检索相关知识";
            case "openviking_forget" -> "删除资源";
            case "task_plan" -> "维护执行计划";
            case "artifact_work" -> "准备工作台产物";
            case "tool_discovery" -> "发现可用工具";
            case "user_memory" -> "处理用户记忆";
            case "skill" -> "处理 Skill";
            default -> titleFor(toolName);
        };
    }

    /** 根据当前状态生成分组摘要，让前端知道这组动作是在进行中还是已经完成。 */
    private String groupSummary(String toolName, String status) {
        if ("running".equals(status)) {
            return switch (groupKind(toolName)) {
                case "openviking_read" -> "正在读取资源";
                case "openviking_browse" -> "正在浏览目录";
                case "openviking_match" -> "正在查找候选";
                case "openviking_search" -> "正在检索知识";
                case "openviking_forget" -> "正在删除资源";
                case "task_plan" -> "正在更新执行计划";
                case "artifact_work" -> "正在准备产物";
                case "tool_discovery" -> "正在查找工具";
                case "user_memory" -> "正在处理记忆";
                case "skill" -> "正在处理 Skill";
                default -> "正在执行工具";
            };
        }
        if ("failed".equals(status)) {
            return "部分操作失败";
        }
        if ("blocked".equals(status)) {
            return "操作已被安全规则阻止";
        }
        return switch (groupKind(toolName)) {
            case "openviking_read" -> "资源读取完成";
            case "openviking_browse" -> "目录浏览完成";
            case "openviking_match" -> "候选定位完成";
            case "openviking_search" -> "知识检索完成";
            case "openviking_forget" -> "资源已删除";
            case "task_plan" -> "执行计划已更新";
            case "artifact_work" -> "产物准备完成";
            case "tool_discovery" -> "工具发现完成";
            case "user_memory" -> "记忆处理完成";
            case "skill" -> "Skill 处理完成";
            default -> "工具执行完成";
        };
    }

    /** 生成作用域键，把“同一片资源范围里的工具动作”稳定聚在一起。 */
    private String scopeKey(String toolName, Map<String, Object> preview) {
        String source = switch (toolName) {
            case "openviking_read", "openviking_list", "openviking_tree", "openviking_grep", "openviking_glob" ->
                    stringValue(preview.get("uri"));
            case "openviking_find", "openviking_search" -> stringValue(preview.get("targetUri"));
            case "openviking_forget" -> stringValue(preview.get("uri"));
            case "createPlan", "updateStatus", "addTask", "removeTask" -> "task_plan";
            case "getResumeGuide", "getOptimizeGuide", "publishArtifact" -> "artifact_work";
            case "toolSearch" -> stringValue(preview.get("toolName"));
            case "rememberUserPreference", "rememberUserMemory", "readUserMemory", "readUserMemoryDetail" -> "user_memory";
            case "openviking_skill_search", "openviking_skill_read", "openviking_skill_files",
                 "openviking_skill_read_file", "openviking_skill_add" -> skillScope(preview);
            default -> "";
        };
        if (source.isBlank()) {
            source = "global";
        }
        return Integer.toHexString(source.hashCode());
    }

    /** 给工具动作生成一句人话描述，说明这把工具这次到底在干嘛。 */
    private String descriptionFor(ToolExecutionRequest request) {
        Map<String, Object> preview = safePreview(request.arguments());
        String toolName = request.name();
        return switch (toolName) {
            case "openviking_list", "openviking_tree" -> describeScope(preview.get("uri"), "viking://");
            case "openviking_read" -> describeRead(preview);
            case "openviking_glob" -> describePattern(preview, "pattern", "在路径中匹配");
            case "openviking_grep" -> describePattern(preview, "pattern", "在内容中匹配");
            case "openviking_find", "openviking_search" -> describeQuery(preview);
            case "openviking_forget" -> describeScope(preview.get("uri"), "viking://");
            case "toolSearch" -> "查询：" + fallback(stringValue(preview.get("toolName")), "可用工具");
            case "getCurrentTime" -> "读取运行环境时间";
            case "createPlan" -> "拆解本轮内部执行步骤";
            case "updateStatus" -> "任务：" + fallback(stringValue(preview.get("taskId")), "(未指定)")
                    + " → " + fallback(stringValue(preview.get("newStatus")), "(未指定)");
            case "addTask" -> "追加：" + fallback(stringValue(preview.get("description")), "新任务");
            case "removeTask" -> "移除：" + fallback(stringValue(preview.get("taskId")), "(未指定)");
            case "publishArtifact" -> "类型：" + fallback(stringValue(preview.get("type")), fallback(stringValue(preview.get("artifactType")), "artifact"));
            case "generateMindmap" -> "生成结构图";
            case "getResumeGuide" -> "获取结构化简历生成规则";
            case "getOptimizeGuide" -> "获取 JD 匹配与优化规则";
            case "rememberUserPreference" -> "写入偏好：" + fallback(stringValue(preview.get("preferenceKey")), "用户偏好");
            case "rememberUserMemory" -> "写入记忆：" + fallback(stringValue(preview.get("memoryKey")), "用户记忆");
            case "readUserMemory" -> "读取用户记忆";
            case "readUserMemoryDetail" -> "读取详情：" + fallback(stringValue(preview.get("memoryKey")), "用户记忆");
            case "openviking_skill_search" -> "查询：" + fallback(stringValue(preview.get("query")), "Skill");
            case "openviking_skill_read" -> "读取 Skill：" + fallback(stringValue(preview.get("name")), "(未指定)");
            case "openviking_skill_files" -> "文件树：" + fallback(stringValue(preview.get("name")), "(未指定)");
            case "openviking_skill_read_file" -> describeSkillFile(preview);
            case "openviking_skill_add" -> "写入 Skill";
            default -> toolName;
        };
    }

    /** 提取工具涉及到的资源 URI，方便前端做资源跳转或范围提示。 */
    private List<String> resourceUrisFor(String toolName, Map<String, Object> preview) {
        if (!OPEN_VIKING_TOOLS.contains(toolName)) {
            return List.of();
        }

        List<String> uris = new ArrayList<>();
        switch (toolName) {
            case "openviking_read", "openviking_list", "openviking_tree", "openviking_grep",
                 "openviking_glob", "openviking_forget" -> addIfPresent(uris, stringValue(preview.get("uri")));
            case "openviking_find", "openviking_search" -> addIfPresent(uris, stringValue(preview.get("targetUri")));
            case "openviking_skill_search" -> addIfPresent(uris, "viking://agent/skills/");
            case "openviking_skill_read" -> addIfPresent(uris, skillUri(preview, "SKILL.md"));
            case "openviking_skill_files" -> addIfPresent(uris, skillUri(preview, ""));
            case "openviking_skill_read_file" -> addIfPresent(uris, skillUri(preview, stringValue(preview.get("path"))));
            default -> {
                // Other OpenViking tools may not expose a stable user-visible path.
            }
        }
        return uris;
    }

    /** 如果值存在且未重复，就把它加入 URI 列表。 */
    private void addIfPresent(List<String> values, String value) {
        String normalized = value != null ? value.trim() : "";
        if (normalized.isBlank() || values.contains(normalized)) {
            return;
        }
        values.add(normalized);
    }

    /** 计算 Skill 相关动作的作用域。 */
    private String skillScope(Map<String, Object> preview) {
        String uri = skillUri(preview, stringValue(preview.get("path")));
        return uri.isBlank() ? "skill" : uri;
    }

    /** 根据 Skill 名和文件路径拼出稳定的资源 URI。 */
    private String skillUri(Map<String, Object> preview, String path) {
        String name = stringValue(preview.get("name")).trim();
        if (name.isBlank()) {
            return "";
        }
        String baseUri = "viking://agent/skills/" + trimSlashes(name);
        String normalizedPath = path != null ? path.trim().replace('\\', '/') : "";
        if (normalizedPath.isBlank()) {
            return baseUri + "/";
        }
        return baseUri + "/" + trimLeadingSlashes(normalizedPath);
    }

    /** 去掉首尾多余斜杠，让 Skill URI 更稳定。 */
    private String trimSlashes(String value) {
        return trimLeadingSlashes(value).replaceAll("/+$", "");
    }

    /** 只去掉前导斜杠，避免拼 URI 时出现双斜杠。 */
    private String trimLeadingSlashes(String value) {
        return value != null ? value.replaceAll("^/+", "") : "";
    }

    /** 生成 Skill 文件读取动作的描述。 */
    private String describeSkillFile(Map<String, Object> preview) {
        String name = fallback(stringValue(preview.get("name")), "(未指定)");
        String path = fallback(stringValue(preview.get("path")), "文件");
        return "读取 Skill 文件：" + name + " · " + path;
    }

    /** 生成人话范围说明，适合 list/tree/forget 这种以 URI 为中心的工具。 */
    private String describeScope(Object uri, String fallback) {
        String target = stringValue(uri);
        return "目标：" + (target.isBlank() ? fallback : target);
    }

    /** 生成读取动作描述，并补出层级信息。 */
    private String describeRead(Map<String, Object> preview) {
        String uri = stringValue(preview.get("uri"));
        String level = stringValue(preview.get("level"));
        if (!level.isBlank()) {
            return "目标：" + (uri.isBlank() ? "viking://" : uri) + " · " + level;
        }
        return "目标：" + (uri.isBlank() ? "viking://" : uri);
    }

    /** 生成按路径或内容匹配时的说明，像告诉用户“我在什么范围里搜什么”。 */
    private String describePattern(Map<String, Object> preview, String key, String verb) {
        String pattern = stringValue(preview.get(key));
        String uri = stringValue(preview.get("uri"));
        String scope = uri.isBlank() ? "viking://" : uri;
        return verb + "：" + (pattern.isBlank() ? "(空)" : pattern) + " · " + scope;
    }

    /** 生成检索型工具的描述。 */
    private String describeQuery(Map<String, Object> preview) {
        String query = stringValue(preview.get("query"));
        String targetUri = stringValue(preview.get("targetUri"));
        if (!targetUri.isBlank()) {
            return "查询：" + (query.isBlank() ? "(空)" : query) + " · " + targetUri;
        }
        return "查询：" + (query.isBlank() ? "(空)" : query);
    }

    /** 从原始 arguments 中提取安全可展示字段，避免敏感信息跑到前端。 */
    private Map<String, Object> safePreview(String arguments) {
        Map<String, Object> preview = new LinkedHashMap<>();
        if (arguments == null || arguments.isBlank()) {
            return preview;
        }
        try {
            JsonNode root = objectMapper.readTree(arguments);
            if (!root.isObject()) {
                return preview;
            }
            root.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if (isSafePreviewKey(key)) {
                    preview.put(key, truncate(entry.getValue().isValueNode() ? entry.getValue().asText() : entry.getValue().toString(), MAX_PREVIEW_VALUE_CHARS));
                }
            });
        } catch (Exception e) {
            log.debug("[ToolActionEventService] arguments parse failed: {}", e.getMessage());
        }
        return preview;
    }

    /** 判断某个参数键是不是允许暴露到预览里。 */
    private boolean isSafePreviewKey(String key) {
        String lowerKey = key != null ? key.toLowerCase() : "";
        if (lowerKey.contains("password")
                || lowerKey.contains("token")
                || lowerKey.contains("secret")
                || lowerKey.contains("apikey")
                || lowerKey.contains("api_key")
                || lowerKey.contains("credential")) {
            return false;
        }
        return SAFE_PREVIEW_KEYS.contains(key);
    }

    /** 对工具原始结果做摘要，把大段文本压缩成一句结果说明。 */
    private String summarizeResult(String toolName, String result) {
        if (result == null || result.isBlank()) {
            return "工具执行完成，未返回可展示摘要";
        }
        String normalized = result.trim();
        if (normalized.contains("result=empty")) {
            return "没有找到匹配结果";
        }
        if (normalized.contains("status=error") || normalized.toLowerCase().contains(" failed:")) {
            return summarizeError(normalized);
        }
        Integer total = extractTotal(normalized);
        if (total != null) {
            return total == 0 ? "没有找到匹配结果" : "找到 " + total + " 条相关结果";
        }
        Integer resultCount = countResultItems(normalized);
        if (resultCount != null && resultCount > 0) {
            return "找到 " + resultCount + " 条相关结果";
        }
        return switch (toolName) {
            case "openviking_list" -> "目录列表读取完成";
            case "openviking_tree" -> "目录树读取完成";
            case "openviking_read" -> "资源读取完成";
            case "openviking_glob" -> "路径匹配完成";
            case "openviking_grep" -> "内容搜索完成";
            case "openviking_find", "openviking_search" -> "检索完成";
            case "openviking_forget" -> "资源已删除";
            case "toolSearch" -> summarizeToolSearch(normalized);
            case "getCurrentTime" -> "当前时间已获取";
            case "createPlan" -> summarizeTaskPlan(normalized, "执行计划已创建");
            case "updateStatus" -> summarizeTaskPlan(normalized, "任务状态已更新");
            case "addTask" -> summarizeTaskPlan(normalized, "任务已追加");
            case "removeTask" -> summarizeTaskPlan(normalized, "任务已移除");
            case "publishArtifact" -> summarizeArtifact(normalized);
            case "generateMindmap" -> "思维导图已生成";
            case "getResumeGuide" -> "简历生成指南已读取";
            case "getOptimizeGuide" -> "简历优化指南已读取";
            case "rememberUserPreference" -> "用户偏好已记录";
            case "rememberUserMemory" -> "用户记忆已记录";
            case "readUserMemory" -> summarizeMemory(normalized, "用户记忆已读取");
            case "readUserMemoryDetail" -> summarizeMemory(normalized, "记忆详情已读取");
            case "openviking_skill_search" -> "Skill 检索完成";
            case "openviking_skill_read" -> "Skill 已读取";
            case "openviking_skill_files" -> "Skill 文件树已读取";
            case "openviking_skill_read_file" -> "Skill 文件已读取";
            case "openviking_skill_add" -> "Skill 已写入";
            default -> "工具执行完成";
        };
    }

    /** 对错误文本做摘要压缩，避免时间线卡片被异常堆栈刷满。 */
    private String summarizeError(String error) {
        if (error == null || error.isBlank()) {
            return "工具执行失败";
        }
        return truncate(error.replace('\n', ' ').trim(), MAX_SUMMARY_CHARS);
    }

    /** 给被安全规则阻断的动作生成统一错误说明。 */
    private String summarizeBlocked(String reason) {
        String summary = summarizeError(reason);
        return summary.isBlank() ? "操作已被安全规则阻止" : "操作已被安全规则阻止：" + summary;
    }

    /** 汇总 toolSearch 结果，告诉用户是命中了工具说明还是若干候选项。 */
    private String summarizeToolSearch(String result) {
        if (result.contains("\"name\"")) {
            return "已加载匹配工具的完整说明";
        }
        Integer count = countBullets(result);
        return count != null && count > 0 ? "找到 " + count + " 个候选工具" : "工具查询完成";
    }

    /** 汇总任务计划工具结果，重点告诉用户任务数有没有变化。 */
    private String summarizeTaskPlan(String result, String fallback) {
        try {
            JsonNode root = objectMapper.readTree(result);
            if (root.isArray()) {
                return fallback + "，共 " + root.size() + " 个任务";
            }
        } catch (Exception ignored) {
            // Fall through to text summary.
        }
        if (result.startsWith("错误：")) {
            return summarizeError(result);
        }
        return fallback;
    }

    /** 汇总 publishArtifact 的结果，把不同 artifact 类型翻译成清晰的交付说明。 */
    private String summarizeArtifact(String result) {
        try {
            JsonNode root = objectMapper.readTree(result);
            String type = root.path("type").asText("");
            if (!type.isBlank() && !"error".equals(type)) {
                return switch (type) {
                    case "resume" -> "简历产物已发布";
                    case "optimize_result" -> "优化分析产物已发布";
                    case "mindmap" -> "思维导图产物已发布";
                    case "questionnaire" -> "问题清单已发布";
                    default -> "产物已发布：" + type;
                };
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        return "工作台产物已发布";
    }

    /** 汇总记忆读取结果，尽量提炼成数量信息。 */
    private String summarizeMemory(String result, String fallback) {
        if (result.contains("未找到") || result.toLowerCase().contains("not found")) {
            return "未找到匹配记忆";
        }
        Integer count = countBullets(result);
        if (count != null && count > 0) {
            return "读取到 " + count + " 条记忆";
        }
        return fallback;
    }

    /** 从文本结果里提取 `Total:` 总数。 */
    private Integer extractTotal(String text) {
        int totalIndex = text.lastIndexOf("Total:");
        if (totalIndex < 0) {
            return null;
        }
        String tail = text.substring(totalIndex + "Total:".length()).trim();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < tail.length(); i++) {
            char ch = tail.charAt(i);
            if (!Character.isDigit(ch)) {
                break;
            }
            digits.append(ch);
        }
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 统计结果文本里 `Result x` 的出现次数，粗略估算命中项数。 */
    private Integer countResultItems(String text) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf("Result ", index)) >= 0) {
            count++;
            index += "Result ".length();
        }
        return count > 0 ? count : null;
    }

    /** 统计项目符号或编号行数，用来推断结果条目数量。 */
    private Integer countBullets(String text) {
        int count = 0;
        String[] lines = text.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.matches("^\\d+[.)].+")) {
                count++;
            }
        }
        return count > 0 ? count : null;
    }

    /** 安全转字符串。 */
    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    /** 为空时返回兜底值，避免展示字段掉空。 */
    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 截断过长文本，避免前端时间线一条动作撑得太长。 */
    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
