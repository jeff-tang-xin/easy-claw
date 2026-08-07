package com.xinl.easyclaw.config;

import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import com.xinl.easyclaw.role.service.RoleManagementService;
import com.xinl.easyclaw.tool.entity.ToolDefinitionEntity;
import com.xinl.easyclaw.tool.service.ToolManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据初始化配置
 * <p>
 * 应用首次启动时初始化默认角色与内置工具定义
 */
@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    public CommandLineRunner initDefaultRoles(RoleManagementService roleService) {
        return args -> {
            // 确保"主智能体"角色存在（团队模式默认角色，模型配置决定主智能体模型）
            if (roleService.findByName("main").isEmpty()) {
                roleService.create(AgentRoleEntity.builder()
                        .name("main")
                        .displayName("主智能体")
                        .role("AI 团队的主导航者与协调者")
                        .goal("拆解任务、组建子智能体团队、并行调度成员、汇总结果后给出最终答复")
                        .backstory("你是整个 AI 团队的主智能体，负责整体规划与跨子任务的协调，模型留空表示使用全局默认模型，可在角色管理中单独配置。")
                        .temperature(0.4)
                        .model("")
                        .active(true)
                        .build());
                log.info("已确保主智能体角色存在（name=main）");
            }

            if (roleService.findAll().isEmpty()) {
                log.info("初始化默认角色...");

                roleService.create(AgentRoleEntity.builder()
                        .name("code-expert")
                        .displayName("代码专家")
                        .role("资深软件架构师")
                        .goal("帮助用户编写高质量、可维护的代码")
                        .backstory("你拥有10年Java开发经验，精通Spring生态、设计模式、代码重构。你注重代码规范、性能优化和可维护性。")
                        .temperature(0.3)
                        .model("gpt-4")
                        .active(true)
                        .build());

                roleService.create(AgentRoleEntity.builder()
                        .name("file-expert")
                        .displayName("文件操作专家")
                        .role("文件系统运维专家")
                        .goal("高效、安全地管理文件和目录")
                        .backstory("你熟悉各种文件操作，注重数据安全，严格遵守文件沙箱隔离规则。")
                        .temperature(0.2)
                        .model("gpt-4")
                        .active(true)
                        .build());

                roleService.create(AgentRoleEntity.builder()
                        .name("researcher")
                        .displayName("研究分析师")
                        .role("信息研究分析师")
                        .goal("提供准确、全面的信息查询和分析")
                        .backstory("你擅长信息检索、数据分析和知识综合，能够从多个角度分析问题。")
                        .temperature(0.7)
                        .model("gpt-4")
                        .active(true)
                        .build());

                roleService.create(AgentRoleEntity.builder()
                        .name("creative-writer")
                        .displayName("创意作家")
                        .role("创意写作专家")
                        .goal("帮助用户创作富有创意和感染力的内容")
                        .backstory("你是一位经验丰富的作家，擅长各种文体创作，注重语言的表达力和感染力。")
                        .temperature(0.9)
                        .model("gpt-4")
                        .active(false)
                        .build());

                log.info("默认角色初始化完成");
            }
        };
    }

    @Bean
    public CommandLineRunner initDefaultTools(ToolManagementService toolService) {
        return args -> {
            if (toolService.findAll().isEmpty()) {
                log.info("初始化内置工具...");

                toolService.create(ToolDefinitionEntity.builder()
                        .name("file-read")
                        .displayName("文件读取")
                        .description("读取工作目录下的文件内容")
                        .toolGroup("FILE")
                        .parameters("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"相对路径\"}},\"required\":[\"path\"]}")
                        .implementation("BUILTIN")
                        .implementationConfig("FileManagerSkill.readFile")
                        .enabled(true)
                        .isSystem(true)
                        .build());

                toolService.create(ToolDefinitionEntity.builder()
                        .name("file-write")
                        .displayName("文件写入")
                        .description("向工作目录下的文件写入内容")
                        .toolGroup("FILE")
                        .parameters("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}")
                        .implementation("BUILTIN")
                        .implementationConfig("FileManagerSkill.writeFile")
                        .enabled(true)
                        .isSystem(true)
                        .build());

                toolService.create(ToolDefinitionEntity.builder()
                        .name("file-list")
                        .displayName("目录列表")
                        .description("列出指定目录下的文件")
                        .toolGroup("FILE")
                        .parameters("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}")
                        .implementation("BUILTIN")
                        .implementationConfig("FileManagerSkill.listDirectory")
                        .enabled(true)
                        .isSystem(true)
                        .build());

                toolService.create(ToolDefinitionEntity.builder()
                        .name("code-format")
                        .displayName("代码格式化")
                        .description("格式化 Java / JSON / XML 代码")
                        .toolGroup("CODE")
                        .parameters("{\"type\":\"object\",\"properties\":{\"language\":{\"type\":\"string\",\"enum\":[\"java\",\"json\",\"xml\"]},\"code\":{\"type\":\"string\"}},\"required\":[\"language\",\"code\"]}")
                        .implementation("BUILTIN")
                        .implementationConfig("CodeFormatterSkill.format")
                        .enabled(true)
                        .isSystem(true)
                        .build());

                toolService.create(ToolDefinitionEntity.builder()
                        .name("web-search")
                        .displayName("网络搜索")
                        .description("执行网络搜索和 HTTP 请求")
                        .toolGroup("WEB")
                        .parameters("{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"},\"method\":{\"type\":\"string\",\"enum\":[\"GET\",\"POST\"]}},\"required\":[\"url\"]}")
                        .implementation("BUILTIN")
                        .implementationConfig("WebSearchSkill.httpGet")
                        .enabled(true)
                        .isSystem(true)
                        .build());

                log.info("内置工具初始化完成");
            }
        };
    }
}
