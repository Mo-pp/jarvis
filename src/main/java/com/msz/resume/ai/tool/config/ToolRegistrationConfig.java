package com.msz.resume.ai.tool.config;

import com.msz.resume.ai.chat.tooling.ArtifactTool;
import com.msz.resume.ai.chat.tooling.AskUserQuestionTool;
import com.msz.resume.ai.chat.tooling.SpawnAgentTool;
import com.msz.resume.ai.tool.impl.GetCurrentTimeTool;
import com.msz.resume.ai.chat.tooling.MindmapTool;
import com.msz.resume.ai.integrations.openviking.tooling.OpenVikingSearchTool;
import com.msz.resume.ai.integrations.openviking.tooling.OpenVikingSkillTool;
import com.msz.resume.ai.integrations.openviking.tooling.OpenVikingSkillWriteTool;
import com.msz.resume.ai.memory.tooling.ReadUserMemoryDetailTool;
import com.msz.resume.ai.memory.tooling.ReadUserMemoryTool;
import com.msz.resume.ai.memory.tooling.RememberUserMemoryTool;
import com.msz.resume.ai.memory.tooling.RememberUserPreferenceTool;
import com.msz.resume.ai.resume.tooling.ResumeGuideTool;
import com.msz.resume.ai.resume.tooling.ResumeOptimizeGuideTool;
import com.msz.resume.ai.chat.tooling.TaskPlanTool;
import com.msz.resume.ai.tool.impl.ToolSearchTool;
import com.msz.resume.ai.tool.registry.ToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * 工具注册配置类
 *
 * 在应用启动时自动注册所有工具
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ToolRegistrationConfig {

    private final ToolRegistry toolRegistry;
    private final GetCurrentTimeTool getCurrentTimeTool;
    private final ToolSearchTool toolSearchTool;
    private final AskUserQuestionTool askUserQuestionTool;
    private final ArtifactTool artifactTool;
    private final MindmapTool mindmapTool;
    private final TaskPlanTool taskPlanTool;
    private final SpawnAgentTool spawnAgentTool;
    private final OpenVikingSearchTool openVikingSearchTool;
    private final OpenVikingSkillTool openVikingSkillTool;
    private final OpenVikingSkillWriteTool openVikingSkillWriteTool;
    private final ReadUserMemoryTool readUserMemoryTool;
    private final ReadUserMemoryDetailTool readUserMemoryDetailTool;
    private final RememberUserMemoryTool rememberUserMemoryTool;
    private final RememberUserPreferenceTool rememberUserPreferenceTool;
    private final ResumeGuideTool resumeGuideTool;
    private final ResumeOptimizeGuideTool resumeOptimizeGuideTool;

    /**
     * 应用启动后自动注册工具
     */
    @PostConstruct
    public void registerAllTools() {
        log.info("========== 开始注册工具 ==========");

        // 注册核心工具（@CoreTool）
        toolRegistry.registerToolsFromObject(getCurrentTimeTool);
        toolRegistry.registerToolsFromObject(toolSearchTool);
        toolRegistry.registerToolsFromObject(askUserQuestionTool);
        toolRegistry.registerToolsFromObject(artifactTool);

        // 注册思维导图工具（延迟工具）
        toolRegistry.registerToolsFromObject(mindmapTool);

        // 注册任务规划工具（核心工具）
        toolRegistry.registerToolsFromObject(taskPlanTool);

        // 注册子Agent派发工具（延迟工具）
        toolRegistry.registerToolsFromObject(spawnAgentTool);

        // 注册 OpenViking canonical 检索工具族（核心工具）
        toolRegistry.registerToolsFromObject(openVikingSearchTool);

        // 注册 OpenViking Skill 读取工具族（核心工具）
        toolRegistry.registerToolsFromObject(openVikingSkillTool);

        // 注册 OpenViking Skill 写入工具族（延迟工具）
        toolRegistry.registerToolsFromObject(openVikingSkillWriteTool);

        // 注册用户长期记忆工具（核心工具）
        toolRegistry.registerToolsFromObject(readUserMemoryTool);
        toolRegistry.registerToolsFromObject(readUserMemoryDetailTool);
        toolRegistry.registerToolsFromObject(rememberUserMemoryTool);
        toolRegistry.registerToolsFromObject(rememberUserPreferenceTool);

        // 注册简历相关工具（核心工具）
        toolRegistry.registerToolsFromObject(resumeGuideTool);
        toolRegistry.registerToolsFromObject(resumeOptimizeGuideTool);

        log.info("========== 工具注册完成，共 {} 个工具（核心: {}, 延迟: {}）==========",
                toolRegistry.getToolCount(),
                toolRegistry.getCoreToolNames().size(),
                toolRegistry.getDeferredToolNames().size());
    }
}
