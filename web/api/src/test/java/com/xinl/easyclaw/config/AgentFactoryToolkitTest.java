package com.xinl.easyclaw.config;

import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 回归测试：主控 Toolkit 必须包含全部内置工具类的工具。
 * <p>
 * 背景（真实线上 bug）：{@code Toolkit.ToolRegistration.tool(Object)} 是<b>赋值</b>而非追加
 * （Toolkit.java:827 {@code this.toolObject = toolObject}），且 {@code apply()} 只注册单个
 * 对象。历史写法在同一个 registration 上连调 6 次 {@code tool(...)}，后者覆盖前者，最终
 * 只有最后一个 knowledgeTools 生效，其余 5 类工具静默丢失。
 * <p>
 * 该缺陷曾逃过既有单测：{@code SubagentLoaderSpiTest} 只断言子 Agent 声明的
 * {@code getTools()} <b>字符串列表</b>含 "blackboard_append"，而子 Agent 的 toolkit 由
 * {@code HarnessAgentBuilderSupport.allowlistedInheritedToolkit} 从父 toolkit
 * <b>做减法</b>得到——白名单里有、父 toolkit 里没有的工具不会被创建也不会报错，直到模型
 * 真去调用才抛 "Tool not found"。故此处必须断言 {@code getTool(name)} 能取到<b>实例</b>。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("AgentFactory 主控 Toolkit 装配")
class AgentFactoryToolkitTest {

    /** 六个内置工具类各取一个代表性 @Tool 名，覆盖注册顺序的首/中/尾。 */
    private static final List<String> EXPECTED_TOOLS = List.of(
            "list_directory",      // FileOperationTools（第 1 个注册，最容易被覆盖）
            "web_search",          // WebSearchTools
            "analyze_code",        // CodeGenerationTools
            "run_skill_script",    // SkillScriptTools
            "blackboard_append",   // BlackboardTools（线上报 Tool not found 的那个）
            "blackboard_read",
            "knowledge_write"      // KnowledgeTools（第 6 个，覆盖 bug 下唯一幸存者）
    );

    @Autowired
    private AgentFactory agentFactory;

    @MockitoBean
    private CloudFeatureGate featureGate;

    @Test
    @DisplayName("六类内置工具全部注册，不因 registration 覆盖而丢失")
    void workspaceToolkitContainsAllBuiltinTools() {
        Toolkit toolkit = agentFactory.createWorkspaceToolkit();

        for (String name : EXPECTED_TOOLS) {
            // 断言取到实例而非仅存在于名单：这是与 "Tool not found" 同一条代码路径
            // （ToolExecutor.java:185 toolRegistry.getTool(...) 返回 null 即报错）
            assertNotNull(toolkit.getTool(name),
                    "工具 " + name + " 未注册到主控 Toolkit —— "
                            + "检查 AgentFactory 是否又退化成单个 registration 连调 tool()");
        }
    }

    @Test
    @DisplayName("工具总数远超单个工具类，反向锁死「只注册了最后一个」的退化")
    void toolkitIsNotCollapsedToSingleToolClass() {
        Toolkit toolkit = agentFactory.createWorkspaceToolkit();

        // KnowledgeTools 单类仅 3 个工具；覆盖 bug 下总数会塌缩到个位数。
        // 取 15 作阈值：既远高于任何单类工具数，又给后续增删工具留出余量。
        int count = toolkit.getToolSchemas().size();
        assertTrue(count >= 15,
                "主控 Toolkit 只有 " + count + " 个工具，疑似多数工具类被覆盖丢失");
    }

    @Test
    @DisplayName("cloud 目录禁用的工具（含框架工具）从主控 Toolkit 摘除")
    void cloudDisabledToolsRemovedFromToolkit() {
        when(featureGate.disabledTools()).thenReturn(Set.of("read_file", "web_search"));

        Toolkit toolkit = agentFactory.createWorkspaceToolkit();

        assertNull(toolkit.getTool("read_file"), "框架工具 read_file 应被 cloud 目录摘除");
        assertNull(toolkit.getTool("web_search"), "web_search 应被 cloud 目录摘除");
        // 未列入禁用集的工具不受影响
        assertNotNull(toolkit.getTool("list_directory"));
        assertNotNull(toolkit.getTool("blackboard_append"));
    }
}
