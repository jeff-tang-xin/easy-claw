package com.xinl.easyclaw.config.seed;

import com.xinl.easyclaw.config.SystemHomePaths;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.repository.McpServiceRepository;
import com.xinl.easyclaw.scenario.entity.ScenarioEntity;
import com.xinl.easyclaw.scenario.repository.ScenarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 系统内置数据播种器。
 * <p>
 * 每次启动都会执行 UPSERT，确保 SYSTEM 级别的内置 MCP 模板始终存在。
 * MCP 模板标记 isTemplate=true，用户可复制使用但不可修改系统级模板。
 * <p>
 * 内置 Skill 已迁移至 resources/skills/，由 {@link com.xinl.easyclaw.config.BuiltinSkillsInstaller} 负责复制到用户目录。
 * <p>
 * 场景（Scenario）：播种内置子 Agent（planner/coder/reviewer，仅当全局目录缺失时）
 * 和三个内置场景（通用编程 / 团队协作开发 / 代码评审）。
 */
@Component
public class SystemDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(SystemDataSeeder.class);

    private final McpServiceRepository mcpRepo;
    private final ScenarioRepository scenarioRepo;
    private final DataSource dataSource;

    public SystemDataSeeder(McpServiceRepository mcpRepo, ScenarioRepository scenarioRepo, DataSource dataSource) {
        this.mcpRepo = mcpRepo;
        this.scenarioRepo = scenarioRepo;
        this.dataSource = dataSource;
    }

    public void seedAll() {
        seedEmailTemplate();
        seedGitTemplate();
        seedDingtalkTemplate();
        seedFeishuTemplate();
        seedWecomTemplate();
        seedBuiltinSubagents();
        seedScenarios();
        log.info("系统内置数据播种完成");
    }

    /**
     * 播种内置子 Agent（仅当全局 subagents 目录缺少对应文件时写入，
     * 用户自己创建/修改过的同名声明不会被覆盖）
     */
    private void seedBuiltinSubagents() {
        Path dir = SystemHomePaths.globalSubagentsDir();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log.warn("创建全局子 Agent 目录失败: {}", e.getMessage());
            return;
        }
        seedSubagent(dir, "planner", "任务规划专家：把复杂需求拆解为清晰、可执行的子任务清单", """
                你是任务规划专家（planner）。你的职责：
                1. 理解用户需求或上级交给的任务，分析目标、约束和验收标准。
                2. 把任务拆解为有序的子任务清单（每个子任务单一、明确、可验证）。
                3. 标注子任务之间的依赖关系与可并行项。
                4. 指出风险点与需要提前确认的信息。
                输出格式：编号任务清单 + 每项的验收标准。不要执行任务本身。""");
        seedSubagent(dir, "coder", "代码实现专家：按任务指令完成高质量代码实现与修复", """
                你是代码实现专家（coder）。你的职责：
                1. 按任务指令完成代码实现（新功能、缺陷修复、重构）。
                2. 实现前先阅读相关代码/文件，理解上下文；实现保持与现有风格一致。
                3. 完成后自行检查：编译/语法正确、边界情况处理、无明显副作用。
                4. 汇报：改了哪些文件、关键决策、遗留问题。不做与任务无关的修改。""");
        seedSubagent(dir, "reviewer", "代码评审专家：检查产出质量并给出具体改进建议", """
                你是代码评审专家（reviewer）。你的职责：
                1. 评审给定代码/产出：正确性、可读性、潜在缺陷、安全隐患、性能。
                2. 对照任务的验收标准逐条核对是否达成。
                3. 按严重程度（阻塞/建议/风格）输出问题清单，每条给出具体位置与修改建议。
                4. 给出结论：通过 / 有条件通过 / 需要返工。不执行代码修改。""");
    }

    private void seedSubagent(Path dir, String name, String description, String prompt) {
        Path file = dir.resolve(name + ".md");
        if (Files.exists(file)) {
            return;
        }
        try {
            Files.writeString(file, "---\ndescription: " + description + "\nsteps: 15\n---\n\n" + prompt + "\n");
            log.info("播种内置子 Agent: {}", name);
        } catch (Exception e) {
            log.warn("播种子 Agent {} 失败: {}", name, e.getMessage());
        }
    }

    /**
     * 播种内置场景（已存在同名场景则跳过，不覆盖用户修改）
     */
    private void seedScenarios() {
        ensureColumn("scenarios", "mode", "TEXT NOT NULL DEFAULT 'single'");
        upsertScenario("general-coding", "通用编程", "💻",
                "默认编程助手模式：需求分析 → 方案设计 → 实现 → 自测验证的闭环工作流。",
                "single", """
                        工作方式：
                        1. 先理解需求与现有代码结构，必要时阅读相关文件，不要凭猜测动手。
                        2. 方案有取舍时简要说明选项与理由，再按最优方案实现。
                        3. 实现完成后主动自测（编译/运行/检查关键路径），确认可用再汇报。
                        4. 汇报格式：做了什么 / 关键改动 / 如何验证 / 遗留风险。""", null);

        upsertScenario("team-dev", "团队协作开发", "🤝",
                "多智能体编排：planner 拆解 → coder 实现 ∥ reviewer 评审（并行）→ 主控汇总交付。",
                "team", """
                        你本场景中的角色是编排者（Orchestrator）：负责任务分发、进度把控与最终汇总，
                        具体专项工作交给子 Agent 完成，不要亲自重复子 Agent 的工作。""", """
                        {"steps":[
                          {"subagent":"planner","instruction":"分析需求并输出子任务清单（含验收标准与依赖关系）","parallel":false},
                          {"subagent":"coder","instruction":"按规划清单完成代码实现，自测通过后汇报改动","parallel":false},
                          {"subagent":"reviewer","instruction":"评审 coder 的改动：对照验收标准逐条核对，输出问题清单与结论","parallel":true}
                        ]}""");

        upsertScenario("code-review", "代码评审会", "🔍",
                "多智能体编排：reviewer 全面评审 ∥ coder 复核可运行性（并行）→ 主控汇总裁决。",
                "team", """
                        你本场景中的角色是评审会主持人：组织评审、汇总意见、给出最终裁决结论。
                        评审意见要具体到文件/行为位置，可执行可验证。""", """
                        {"steps":[
                          {"subagent":"reviewer","instruction":"全面评审指定代码：正确性、可读性、缺陷、安全、性能，按严重程度输出问题清单","parallel":false},
                          {"subagent":"coder","instruction":"从可运行性角度复核：编译/依赖/调用链是否完整，指出无法落地的问题","parallel":true}
                        ]}""");
    }

    private void upsertScenario(String name, String displayName, String icon, String description,
                                String mode, String systemPrompt, String workflow) {
        Optional<ScenarioEntity> existing = scenarioRepo.findByName(name);
        if (existing.isPresent()) {
            return;
        }
        scenarioRepo.save(ScenarioEntity.builder()
                .name(name)
                .displayName(displayName)
                .icon(icon)
                .description(description)
                .mode(mode)
                .systemPrompt(systemPrompt)
                .workflow(workflow)
                .active(true)
                .builtin(true)
                .build());
        log.info("播种内置场景: {} ({})", displayName, mode);
    }

    private void seedEmailTemplate() {
        String impl = """
                {"method":"POST","url":"https://api.resend.com/emails","bodyMode":"json","params":{"from":{"in":"body","type":"string","required":true,"description":"发件人，如 'Name <email@domain.com>'"},"to":{"in":"body","type":"array","required":true,"description":"收件人列表"},"subject":{"in":"body","type":"string","required":true,"description":"邮件主题"},"html":{"in":"body","type":"string","description":"HTML 正文"},"text":{"in":"body","type":"string","description":"纯文本正文"}}}""";
        String headers = """
                {"Authorization":"Bearer YOUR_RESEND_API_KEY"}""";
        upsertMcpTemplate("email-smtp", "邮件发送", """
                通过 SMTP 协议发送邮件。
                配置前请在 headers 中填写 Authorization（如果用 API Key 模式）或在 env 中设置 SMTP_HOST/SMTP_USER/SMTP_PASS。
                默认使用 HTTP_TOOL 桥接方式，直接调用邮件服务商的 REST API（如 Resend、SendGrid、Mailgun）。
                如使用 SMTP 协议，建议自行安装 @modelcontextprotocol/server-email 后改为 STDIO 连接。""",
                impl, headers);
    }

    private void seedGitTemplate() {
        String impl = """
                {"method":"GET","url":"https://api.github.com/repos/{owner}/{repo}/issues","params":{"owner":{"in":"path","type":"string","required":true,"description":"仓库 owner"},"repo":{"in":"path","type":"string","required":true,"description":"仓库名"},"state":{"in":"query","type":"string","description":"open/closed/all"}}}""";
        String headers = """
                {"Authorization":"Bearer YOUR_GITHUB_TOKEN","Accept":"application/vnd.github+json","X-GitHub-Api-Version":"2022-11-28"}""";
        upsertMcpTemplate("github", "GitHub 集成", """
                操作 GitHub 仓库：创建 Issue/PR、查看代码、搜索、管理 Workflow。
                需要在 headers 中填写 GitHub Personal Access Token。
                也可改为 STDIO 模式连接官方 @modelcontextprotocol/server-github。""",
                impl, headers);
    }

    private void seedDingtalkTemplate() {
        String impl = """
                {"method":"POST","url":"https://oapi.dingtalk.com/robot/send?access_token={token}","bodyMode":"json","params":{"token":{"in":"query","type":"string","required":true,"description":"Webhook access_token"},"msgtype":{"in":"body","type":"string","required":true,"description":"消息类型: text/markdown/link/actionCard"},"content":{"in":"body","type":"object","description":"消息内容对象"}}}""";
        String headers = """
                {"Content-Type":"application/json"}""";
        upsertMcpTemplate("dingtalk", "钉钉机器人", """
                通过钉钉自定义机器人 Webhook 发送群消息。
                需要在 url 中替换 WEBHOOK_TOKEN 为你的机器人 Webhook 地址里的 access_token 参数值。
                消息类型支持 text/markdown/link/actionCard。""",
                impl, headers);
    }

    private void seedFeishuTemplate() {
        String impl = """
                {"method":"POST","url":"https://open.feishu.cn/open-apis/im/v1/messages","bodyMode":"json","params":{"receive_id_type":{"in":"query","type":"string","required":true,"description":"chat_id/open_id/user_id"},"receive_id":{"in":"body","type":"string","required":true,"description":"接收者 ID"},"msg_type":{"in":"body","type":"string","required":true,"description":"text/post/image/file"},"content":{"in":"body","type":"object","required":true,"description":"消息内容 JSON"}}}""";
        String headers = """
                {"Authorization":"Bearer YOUR_FEISHU_TENANT_ACCESS_TOKEN","Content-Type":"application/json"}""";
        upsertMcpTemplate("feishu", "飞书/Lark 集成", """
                发送飞书群消息、读取文档、操作日历。
                需要在 headers 中填入 tenant_access_token（通过 app_id/app_secret 换取）。
                建议先调用 auth.v3.tenant_access_token.internal 获取 token，再调用消息接口。""",
                impl, headers);
    }

    private void seedWecomTemplate() {
        String impl = """
                {"method":"POST","url":"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key={key}","bodyMode":"json","params":{"key":{"in":"query","type":"string","required":true,"description":"机器人 Webhook Key"},"msgtype":{"in":"body","type":"string","required":true,"description":"text/markdown/image/news"},"text":{"in":"body","type":"object","description":"msgtype=text 时的内容"},"markdown":{"in":"body","type":"object","description":"msgtype=markdown 时的内容"}}}""";
        String headers = """
                {"Content-Type":"application/json"}""";
        upsertMcpTemplate("wecom", "企业微信集成", """
                发送企业微信群机器人消息、调用企业微信 API。
                群机器人需要 Webhook Key；企业内部应用需要 access_token（通过 corpid/corpsecret 换取）。
                支持 text/markdown/image/news 等消息类型。""",
                impl, headers);
    }

    private void upsertMcpTemplate(String name, String displayName, String description,
                                   String implementationConfig, String headers) {
        Optional<McpServiceEntity> existing = mcpRepo.findByNameAndScope(name, "SYSTEM");
        if (existing.isPresent()) {
            McpServiceEntity e = existing.get();
            if (e.getIsTemplate() == null || !e.getIsTemplate()) {
                e.setIsTemplate(true);
                e.setTemplateJson(buildTemplateJson(name, displayName, implementationConfig, headers));
                e.setDescription("【内置模板】" + displayName + " - " + description);
                mcpRepo.save(e);
                log.info("升级 MCP 为模板: {}", name);
            }
            return;
        }

        McpServiceEntity entity = McpServiceEntity.builder()
                .name(name)
                .description("【内置模板】" + displayName + " - " + description)
                .transport("HTTP_TOOL")
                .implementationConfig(implementationConfig)
                .headers(headers)
                .scope("SYSTEM")
                .isTemplate(true)
                .templateJson(buildTemplateJson(name, displayName, implementationConfig, headers))
                .isConnected(false)
                .build();
        mcpRepo.save(entity);
        log.info("播种 MCP 模板: {}", name);
    }

    private String buildTemplateJson(String name, String displayName,
                                     String implementationConfig, String headers) {
        return """
                {
                  "name": "%s",
                  "displayName": "%s",
                  "transport": "HTTP_TOOL",
                  "implementationConfig": %s,
                  "headers": %s
                }""".formatted(name, displayName,
                implementationConfig.replace("\n", " "),
                headers.replace("\n", " "));
    }

    private void ensureColumn(String table, String column, String definition) {
        try (var conn = dataSource.getConnection()) {
            var meta = conn.getMetaData();
            try (var rs = meta.getColumns(null, null, table, column)) {
                if (rs.next()) return;
            }
            try (var stmt = conn.createStatement()) {
                stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                log.info("已补列: {}.{}", table, column);
            }
        } catch (Exception e) {
            log.warn("ensureColumn({}.{}) 失败: {}", table, column, e.getMessage());
        }
    }
}
