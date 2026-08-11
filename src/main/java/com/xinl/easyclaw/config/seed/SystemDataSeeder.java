package com.xinl.easyclaw.config.seed;

import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.repository.McpServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 系统内置数据播种器。
 * <p>
 * 每次启动都会执行 UPSERT，确保 SYSTEM 级别的内置 MCP 模板始终存在。
 * MCP 模板标记 isTemplate=true，用户可复制使用但不可修改系统级模板。
 * <p>
 * 内置 Skill 已迁移至 resources/skills/，由 {@link com.xinl.easyclaw.config.BuiltinSkillsInstaller} 负责复制到用户目录。
 */
@Component
public class SystemDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(SystemDataSeeder.class);

    private final McpServiceRepository mcpRepo;

    public SystemDataSeeder(McpServiceRepository mcpRepo) {
        this.mcpRepo = mcpRepo;
    }

    public void seedAll() {
        seedEmailTemplate();
        seedGitTemplate();
        seedDingtalkTemplate();
        seedFeishuTemplate();
        seedWecomTemplate();
        log.info("系统内置数据播种完成");
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
}
