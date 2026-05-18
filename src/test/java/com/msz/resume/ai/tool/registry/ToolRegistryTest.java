package com.msz.resume.ai.tool.registry;

import com.msz.resume.ai.integrations.openviking.core.config.OpenVikingProperties;
import com.msz.resume.ai.tool.CoreTool;
import com.msz.resume.ai.tool.config.ToolRegistrationConfig;
import com.msz.resume.ai.chat.tooling.ArtifactTool;
import com.msz.resume.ai.chat.tooling.AskUserQuestionTool;
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
import com.msz.resume.ai.chat.tooling.SpawnAgentTool;
import com.msz.resume.ai.chat.tooling.TaskPlanTool;
import com.msz.resume.ai.tool.impl.ToolSearchTool;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 单元测试
 *
 * 测试工具注册、实例存储和核心/延迟工具分区
 *
 * 注意：LangChain4j 的工具名默认是方法名，不是类名
 */
class ToolRegistryTest {

    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry();
    }

    // ==================== 工具实例存储测试 ====================

    @Test
    @DisplayName("注册工具后可以获取实例")
    void testCanGetInstanceAfterRegistration() {
        TestTool tool = new TestTool();
        toolRegistry.registerToolsFromObject(tool);

        List<Object> instances = toolRegistry.getAllToolInstances();

        assertEquals(1, instances.size());
        assertSame(tool, instances.get(0));
    }

    @Test
    @DisplayName("注册多个工具后可以获取所有实例")
    void testCanGetAllInstancesAfterMultipleRegistrations() {
        FirstTestTool tool1 = new FirstTestTool();
        SecondTestTool tool2 = new SecondTestTool();

        toolRegistry.registerToolsFromObject(tool1);
        toolRegistry.registerToolsFromObject(tool2);

        List<Object> instances = toolRegistry.getAllToolInstances();

        assertEquals(2, instances.size());
    }

    @Test
    @DisplayName("空注册中心返回空实例列表")
    void testEmptyRegistryReturnsEmptyList() {
        List<Object> instances = toolRegistry.getAllToolInstances();

        assertNotNull(instances);
        assertTrue(instances.isEmpty());
    }

    // ==================== 移除工具测试 ====================

    @Test
    @DisplayName("移除工具后实例也被移除")
    void testRemoveToolAlsoRemovesInstance() {
        TestTool tool = new TestTool();
        toolRegistry.registerToolsFromObject(tool);

        // LangChain4j 使用方法名作为工具名
        assertTrue(toolRegistry.hasTool("testMethod"));
        assertEquals(1, toolRegistry.getAllToolInstances().size());

        boolean removed = toolRegistry.removeTool("testMethod");

        assertTrue(removed);
        assertFalse(toolRegistry.hasTool("testMethod"));
        assertTrue(toolRegistry.getAllToolInstances().isEmpty());
    }

    // ==================== 清空工具测试 ====================

    @Test
    @DisplayName("清空所有工具后实例也被清空")
    void testClearAllAlsoClearsInstances() {
        toolRegistry.registerToolsFromObject(new FirstTestTool());
        toolRegistry.registerToolsFromObject(new SecondTestTool());

        assertEquals(2, toolRegistry.getAllToolInstances().size());

        toolRegistry.clearAll();

        assertTrue(toolRegistry.getAllToolInstances().isEmpty());
        assertEquals(0, toolRegistry.getToolCount());
    }

    // ==================== 测试工具类 ====================

    /**
     * 基础测试工具
     */
    static class TestTool {
        @Tool("测试工具")
        public String testMethod() {
            return "test";
        }
    }

    /**
     * 第一个测试工具
     */
    static class FirstTestTool {
        @Tool("第一个测试工具")
        public String firstMethod() {
            return "first";
        }
    }

    /**
     * 第二个测试工具
     */
    static class SecondTestTool {
        @Tool("第二个测试工具")
        public String secondMethod() {
            return "second";
        }
    }

    // ==================== 核心工具/延迟工具分区测试 ====================

    @Test
    @DisplayName("标注 @CoreTool 的工具应出现在核心工具列表中")
    void testCoreToolAppearsInCoreList() {
        CoreTestTool coreTool = new CoreTestTool();
        toolRegistry.registerToolsFromObject(coreTool);

        List<String> coreToolNames = toolRegistry.getCoreToolSpecifications()
                .stream().map(spec -> spec.name()).toList();

        assertTrue(coreToolNames.contains("coreMethod"));
        assertTrue(toolRegistry.isCoreTool("coreMethod"));
        assertEquals(0, toolRegistry.getDeferredToolNames().size());
    }

    @Test
    @DisplayName("未标注 @CoreTool 的工具应出现在延迟工具列表中")
    void testDeferredToolAppearsInDeferredList() {
        DeferredTestTool deferredTool = new DeferredTestTool();
        toolRegistry.registerToolsFromObject(deferredTool);

        Set<String> deferredNames = toolRegistry.getDeferredToolNames();

        assertTrue(deferredNames.contains("deferredMethod"));
        assertFalse(toolRegistry.isCoreTool("deferredMethod"));
        assertEquals(0, toolRegistry.getCoreToolNames().size());
    }

    @Test
    @DisplayName("混合注册核心工具和延迟工具应正确分区")
    void testMixedRegistrationCorrectlyPartitioned() {
        CoreTestTool coreTool = new CoreTestTool();
        DeferredTestTool deferredTool = new DeferredTestTool();

        toolRegistry.registerToolsFromObject(coreTool);
        toolRegistry.registerToolsFromObject(deferredTool);

        // 验证核心工具
        Set<String> coreNames = toolRegistry.getCoreToolNames();
        assertEquals(1, coreNames.size());
        assertTrue(coreNames.contains("coreMethod"));

        // 验证延迟工具
        Set<String> deferredNames = toolRegistry.getDeferredToolNames();
        assertEquals(1, deferredNames.size());
        assertTrue(deferredNames.contains("deferredMethod"));

        // 验证 getAllToolSpecifications 返回全部
        assertEquals(2, toolRegistry.getAllToolSpecifications().size());
    }

    @Test
    @DisplayName("getCoreToolSpecifications 返回完整规格")
    void testGetCoreToolSpecificationsReturnsFullSpecs() {
        CoreTestTool coreTool = new CoreTestTool();
        toolRegistry.registerToolsFromObject(coreTool);

        var specs = toolRegistry.getCoreToolSpecifications();

        assertEquals(1, specs.size());
        assertEquals("coreMethod", specs.get(0).name());
        assertNotNull(specs.get(0).description());
    }

    @Test
    @DisplayName("getDeferredToolSpecifications 返回完整规格")
    void testGetDeferredToolSpecificationsReturnsFullSpecs() {
        DeferredTestTool deferredTool = new DeferredTestTool();
        toolRegistry.registerToolsFromObject(deferredTool);

        var specs = toolRegistry.getDeferredToolSpecifications();

        assertEquals(1, specs.size());
        assertEquals("deferredMethod", specs.get(0).name());
        assertNotNull(specs.get(0).description());
    }

    @Test
    @DisplayName("移除工具时应同时从分区集合中移除")
    void testRemoveToolAlsoRemovesFromPartitionSets() {
        CoreTestTool coreTool = new CoreTestTool();
        toolRegistry.registerToolsFromObject(coreTool);

        assertTrue(toolRegistry.getCoreToolNames().contains("coreMethod"));

        toolRegistry.removeTool("coreMethod");

        assertFalse(toolRegistry.getCoreToolNames().contains("coreMethod"));
        assertFalse(toolRegistry.hasTool("coreMethod"));
    }

    @Test
    @DisplayName("清空工具时应同时清空分区集合")
    void testClearAllAlsoClearsPartitionSets() {
        toolRegistry.registerToolsFromObject(new CoreTestTool());
        toolRegistry.registerToolsFromObject(new DeferredTestTool());

        assertEquals(1, toolRegistry.getCoreToolNames().size());
        assertEquals(1, toolRegistry.getDeferredToolNames().size());

        toolRegistry.clearAll();

        assertTrue(toolRegistry.getCoreToolNames().isEmpty());
        assertTrue(toolRegistry.getDeferredToolNames().isEmpty());
    }

    @Test
    @DisplayName("工具注册配置应暴露 canonical OpenViking 核心工具族")
    void testToolRegistrationConfigRegistersCanonicalOpenVikingToolsAsCoreTools() {
        ToolRegistry registry = new ToolRegistry();
        OpenVikingProperties properties = new OpenVikingProperties();
        com.msz.resume.ai.integrations.openviking.core.client.OpenVikingClient openVikingClient =
                new com.msz.resume.ai.integrations.openviking.core.client.OpenVikingClient(properties);
        com.msz.resume.ai.integrations.openviking.core.service.OpenVikingSkillService skillService =
                new com.msz.resume.ai.integrations.openviking.core.service.OpenVikingSkillService(openVikingClient);
        com.msz.resume.ai.integrations.openviking.core.service.OpenVikingUserMemoryService userMemoryService =
                new com.msz.resume.ai.integrations.openviking.core.service.OpenVikingUserMemoryService(openVikingClient);
        ToolRegistrationConfig config = new ToolRegistrationConfig(
                registry,
                new GetCurrentTimeTool(),
                new ToolSearchTool(registry),
                new AskUserQuestionTool(),
                new ArtifactTool(),
                new MindmapTool(),
                new TaskPlanTool(),
                new SpawnAgentTool(),
                new OpenVikingSearchTool(openVikingClient, properties),
                new OpenVikingSkillTool(skillService),
                new OpenVikingSkillWriteTool(skillService),
                new ReadUserMemoryTool(userMemoryService),
                new ReadUserMemoryDetailTool(userMemoryService),
                new RememberUserMemoryTool(userMemoryService),
                new RememberUserPreferenceTool(new com.msz.resume.ai.integrations.openviking.core.service.OpenVikingMemoryService(openVikingClient)),
                new ResumeGuideTool(),
                new ResumeOptimizeGuideTool()
        );

        config.registerAllTools();

        Set<String> expectedOpenVikingCoreTools = Set.of(
                "openviking_read",
                "openviking_list",
                "openviking_tree",
                "openviking_glob",
                "openviking_grep",
                "openviking_find",
                "openviking_search",
                "openviking_forget",
                "openviking_skill_search",
                "openviking_skill_read",
                "openviking_skill_files",
                "openviking_skill_read_file"
        );
        Set<String> coreToolNames = registry.getCoreToolNames();
        Set<String> deferredToolNames = registry.getDeferredToolNames();

        assertTrue(coreToolNames.containsAll(expectedOpenVikingCoreTools));
        expectedOpenVikingCoreTools.forEach(toolName -> assertTrue(registry.isCoreTool(toolName)));
        assertTrue(registry.isCoreTool("createPlan"));
        assertTrue(registry.isCoreTool("updateStatus"));
        assertTrue(registry.isCoreTool("addTask"));
        assertTrue(registry.isCoreTool("removeTask"));
        assertFalse(deferredToolNames.contains("createPlan"));
        assertFalse(deferredToolNames.contains("updateStatus"));
        assertFalse(deferredToolNames.contains("addTask"));
        assertFalse(deferredToolNames.contains("removeTask"));
        assertTrue(registry.hasTool("openviking_skill_add"));
        assertFalse(registry.isCoreTool("openviking_skill_add"));
        assertTrue(deferredToolNames.contains("openviking_skill_add"));
        assertTrue(registry.hasTool("readUserMemory"));
        assertTrue(registry.hasTool("readUserMemoryDetail"));
        assertTrue(coreToolNames.contains("readUserMemory"));
        assertTrue(coreToolNames.contains("readUserMemoryDetail"));
        assertTrue(registry.hasTool("publishArtifact"));
        assertTrue(registry.isCoreTool("publishArtifact"));
        assertTrue(registry.hasTool("getResumeGuide"));
        assertTrue(registry.isCoreTool("getResumeGuide"));
        assertTrue(registry.hasTool("getOptimizeGuide"));
        assertTrue(registry.isCoreTool("getOptimizeGuide"));
        assertFalse(registry.hasTool("openVikingSearch"));
        assertFalse(deferredToolNames.contains("openVikingSearch"));
    }

    // ==================== 分区测试工具类 ====================

    /**
     * 核心测试工具
     */
    @CoreTool
    static class CoreTestTool {
        @Tool("核心测试工具")
        public String coreMethod() {
            return "core";
        }
    }

    /**
     * 延迟测试工具
     */
    static class DeferredTestTool {
        @Tool("延迟测试工具")
        public String deferredMethod() {
            return "deferred";
        }
    }
}
