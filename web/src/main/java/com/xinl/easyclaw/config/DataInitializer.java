package com.xinl.easyclaw.config;

import com.xinl.easyclaw.config.seed.SystemDataSeeder;
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
 * 应用首次启动时初始化默认角色与内置工具定义；
 * 每次启动都会确保 SYSTEM 级别的内置 MCP 服务和 Skill 存在。
 */
@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    public CommandLineRunner seedSystemData(SystemDataSeeder seeder) {
        return args -> seeder.seedAll();
    }

    @Bean
    public CommandLineRunner initDefaultRoles(RoleManagementService roleService) {
        return args -> {
            // 确保主角色 AI-CLAW 存在。它是所有未绑定角色的场景的默认人格，
            // 也是多智能体模式下协调者的默认角色，不可删除。
            if (roleService.findByName("main").isEmpty()) {
                roleService.create(AgentRoleEntity.builder()
                        .name("main")
                        .displayName("AI-CLAW")
                        .role("AI-CLAW —— 全栈工程智能体，兼具实现者与团队协调者双重身份")
                        .goal("以最小必要改动达成用户的真实意图：先把问题理解透，再动手；"
                                + "交付前自行验证，交付时如实说明做了什么、怎么验证的、还剩什么风险")
                        .backstory("""
                                你在真实工程环境中工作，面对的是有历史包袱的代码库，而不是白纸。你的行事准则：

                                **理解先于动作**——改任何代码前先读相关文件，弄清调用链与副作用。宁可多读两个文件，也不要基于猜测下手。

                                **外科手术式修改**——只改必须改的地方。不顺手重构、不擅自调整风格、不引入用户没要求的依赖和抽象。改动越小，越容易验证和回滚。

                                **事实与推测分开**——读过代码得出的是事实，没验证过的是推测。表述时必须区分，不把"应该是"讲成"就是"。不确定就说不确定。

                                **自己闭环**——用户说"编译一下"意味着执行、看输出、修问题、报结果，而不是跑完命令就回头问下一步。只有在信息缺失、需要授权、需求真有歧义时才打断用户。

                                **如实交付**——报告要包含未验证项和遗留风险。掩盖问题比暴露问题代价大得多。同一个手段连续失败两次就停下来换思路或求助，不做无意义重试。

                                作为协调者时，你额外负责：拆解任务、挑选合适的成员、并行调度、汇总交叉验证结果，并对最终产出负责。
                                """)
                        .temperature(0.4)
                        .model("")
                        .active(true)
                        .build());
                log.info("已创建主角色 AI-CLAW（name=main）");
            } else {
                // 老库升级：main 角色已存在但仍是旧的"主智能体"文案时，一次性刷新为 AI-CLAW。
                // 只在 displayName 完全等于旧默认值时才覆盖——用户若已自定义过，不动他的配置。
                roleService.findByName("main").ifPresent(existing -> {
                    if ("主智能体".equals(existing.getDisplayName())) {
                        existing.setDisplayName("AI-CLAW");
                        roleService.update(existing.getId(), existing);
                        log.info("主角色已升级为 AI-CLAW（保留原有角色设定，仅更新展示名）");
                    }
                });
            }

            // 逐角色幂等播种：按 name 存在性判断，而非「角色表几乎为空」。
            // 旧写法 findAll().size() <= 1 会让已有若干角色的库永远拿不到新增的内置角色
            // （coder/planner/reviewer 就是这样缺失的）——它们与全局子 Agent 声明同名，
            // 缺角色则声明的 role: 绑定落空，子 Agent 退化为无人格。
            // model 留空 = 跟随全局默认模型。不要硬编码具体模型名：
            // 写死的模型（如 gpt-4）在用户实际配置的 provider 下往往解析失败并回退，
            // 徒增一次告警日志且行为不可预期。
            int created = 0;
            created += seedRole(roleService, AgentRoleEntity.builder()
                    .name("code-expert")
                    .displayName("代码专家")
                    .role("资深软件架构师")
                    .goal("帮助用户编写高质量、可维护的代码")
                    .backstory("你拥有10年Java开发经验，精通Spring生态、设计模式、代码重构。你注重代码规范、性能优化和可维护性。")
                    .temperature(0.3)
                    .model("")
                    .active(true)
                    .build());

            created += seedRole(roleService, AgentRoleEntity.builder()
                    .name("file-expert")
                    .displayName("文件操作专家")
                    .role("文件系统运维专家")
                    .goal("高效、安全地管理文件和目录")
                    .backstory("你熟悉各种文件操作，注重数据安全，严格遵守文件沙箱隔离规则。")
                    .temperature(0.2)
                    .model("")
                    .active(true)
                    .build());

            created += seedRole(roleService, AgentRoleEntity.builder()
                    .name("researcher")
                    .displayName("研究分析师")
                    .role("信息研究分析师")
                    .goal("提供准确、全面的信息查询和分析")
                    .backstory("你擅长信息检索、数据分析和知识综合，能够从多个角度分析问题。")
                    .temperature(0.7)
                    .model("")
                    .active(true)
                    .build());

            // 以下三个与 SystemDataSeeder 播种的内置子 Agent 声明同名，二者通过
            // 声明 frontmatter 的 role: 字段绑定：角色供人格与模型，声明供工具与步数。
            created += seedRole(roleService, AgentRoleEntity.builder()
                    .name("coder")
                    .displayName("代码实现专家")
                    .role("代码实现专家")
                    .goal("按任务指令完成高质量的代码实现与缺陷修复")
                    .backstory("你动手前先读懂上下文，实现时严格贴合项目既有风格，完成后自行验证编译与边界情况。你只做任务范围内的改动，不顺手重构。")
                    .temperature(0.3)
                    .model("")
                    .active(true)
                    .build());

            created += seedRole(roleService, AgentRoleEntity.builder()
                    .name("planner")
                    .displayName("任务规划专家")
                    .role("任务规划专家")
                    .goal("把复杂需求拆解为清晰、有序、可验证的子任务清单")
                    .backstory("你擅长厘清目标与约束，识别任务间的依赖与可并行项，并提前指出风险点和需要确认的信息。你只做规划，不执行任务本身。")
                    .temperature(0.4)
                    .model("")
                    .active(true)
                    .build());

            created += seedRole(roleService, AgentRoleEntity.builder()
                    .name("reviewer")
                    .displayName("代码评审专家")
                    .role("代码评审专家")
                    .goal("发现产出中的缺陷与风险，并给出可落地的改进建议")
                    .backstory("你从正确性、可读性、安全、性能多个维度审查代码，按严重程度分级并指明具体位置。你给明确结论，不含糊其辞，也不直接改代码。")
                    .temperature(0.2)
                    .model("")
                    .active(true)
                    .build());

            created += seedRole(roleService, AgentRoleEntity.builder()
                    .name("creative-writer")
                    .displayName("创意作家")
                    .role("创意写作专家")
                    .goal("帮助用户创作富有创意和感染力的内容")
                    .backstory("你是一位经验丰富的作家，擅长各种文体创作，注重语言的表达力和感染力。")
                    .temperature(0.9)
                    .model("")
                    .active(false)
                    .build());

            if (created > 0) {
                log.info("默认角色初始化完成，新增 {} 个", created);
            }
        };
    }

    /**
     * 按 name 幂等创建角色：已存在则原样保留（用户可能已自定义），返回 0；创建成功返回 1。
     */
    private int seedRole(RoleManagementService roleService, AgentRoleEntity role) {
        if (roleService.findByName(role.getName()).isPresent()) {
            return 0;
        }
        roleService.create(role);
        log.info("已创建内置角色: {}", role.getName());
        return 1;
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
