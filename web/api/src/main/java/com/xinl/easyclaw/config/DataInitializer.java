package com.xinl.easyclaw.config;

import com.xinl.easyclaw.config.seed.SystemDataSeeder;
import com.xinl.easyclaw.config.seed.WorkspaceSeedService;
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
 * 应用首次启动时初始化内置工具定义；
 * 每次启动都会确保 SYSTEM 级别的内置 MCP 服务和 Skill 存在。
 * <p>
 * 角色播种已随角色系统下线（方案 C）移除——智能体人格由各 SPI Agent 内置提供，
 * 不再写入 DB。
 */
@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    public CommandLineRunner seedSystemData(SystemDataSeeder seeder) {
        return args -> seeder.seedAll();
    }

    @Bean
    public CommandLineRunner seedWorkspaces(WorkspaceSeedService workspaceSeedService) {
        return args -> workspaceSeedService.seedAllWorkspaces();
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
