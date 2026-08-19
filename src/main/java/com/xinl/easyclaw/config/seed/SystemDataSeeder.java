package com.xinl.easyclaw.config.seed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.entity.McpToolEntity;
import com.xinl.easyclaw.mcp.repository.McpServiceRepository;
import com.xinl.easyclaw.mcp.repository.McpToolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 系统内置数据播种器。
 * <p>
 * 每次启动都会执行 UPSERT，确保 SYSTEM 级别的内置 MCP 模板始终存在。
 * 每个 MCP 模板 = 一个 McpService（共享鉴权 headers）+ 多个 McpTool（具体 REST endpoints）。
 */
@Component
public class SystemDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(SystemDataSeeder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpServiceRepository mcpRepo;
    private final McpToolRepository toolRepo;

    public SystemDataSeeder(McpServiceRepository mcpRepo, McpToolRepository toolRepo) {
        this.mcpRepo = mcpRepo;
        this.toolRepo = toolRepo;
    }

    @Transactional
    public void seedAll() {
        seedGithubTemplate();
        seedGmailTemplate();
        seedOutlookTemplate();
        seedEmailTemplate();
        seedDingtalkTemplate();
        seedFeishuTemplate();
        seedWecomTemplate();
        seedJiraTemplate();
        seedStdioTemplates();
        log.info("[SystemDataSeeder] 系统内置 MCP 模板播种完成");
    }

    // ==================== GitHub ====================

    private void seedGithubTemplate() {
        McpServiceEntity svc = upsertService("github", "GitHub 集成",
                "操作 GitHub 仓库：查看 Issue/PR、搜索代码、管理 Workflow、创建 Release。"
                        + "\n需要在 headers 中填写 GitHub Personal Access Token（PAT）。"
                        + "\n推荐权限：repo、workflow、read:org、read:user。",
                """
                        {"Authorization":"Bearer YOUR_GITHUB_PAT","Accept":"application/vnd.github+json","X-GitHub-Api-Version":"2022-11-28"}""",
                "HTTP_TOOL");
        replaceTools(svc, List.of(
                tool("list_issues", "列出仓库 Issue", "获取指定仓库的 issue 列表，支持按状态、标签、负责人筛选",
                        """
                                {"method":"GET","url":"https://api.github.com/repos/{owner}/{repo}/issues","bodyMode":"json","params":{"owner":{"in":"path","type":"string","required":true,"description":"仓库 owner 或 org 名"},"repo":{"in":"path","type":"string","required":true,"description":"仓库名"},"state":{"in":"query","type":"string","defaultValue":"open","exposeToLlm":false,"description":"过滤 open/closed/all"},"labels":{"in":"query","type":"string","description":"按标签名过滤（逗号分隔）"},"assignee":{"in":"query","type":"string","description":"按负责人过滤"},"per_page":{"in":"query","type":"integer","defaultValue":"30","exposeToLlm":false,"description":"每页数量"},"page":{"in":"query","type":"integer","defaultValue":"1","exposeToLlm":false,"description":"页码"}},"preconditions":["已认证","有仓库访问权限"],"effects":["列出指定仓库的 Issue"]}""", 1),
                tool("create_issue", "创建 Issue", "在指定仓库创建一个新的 issue",
                        """
                                {"method":"POST","url":"https://api.github.com/repos/{owner}/{repo}/issues","bodyMode":"json","params":{"owner":{"in":"path","type":"string","required":true,"description":"仓库 owner 或 org 名"},"repo":{"in":"path","type":"string","required":true,"description":"仓库名"},"title":{"in":"body","type":"string","required":true,"description":"Issue 标题"},"body":{"in":"body","type":"string","description":"Issue 正文（支持 Markdown）"},"labels":{"in":"body","type":"array","description":"标签名数组"},"assignees":{"in":"body","type":"array","description":"负责人用户名数组"}},"preconditions":["已认证","有写权限"],"effects":["创建了新的 GitHub Issue"]}""", 2),
                tool("get_issue", "获取 Issue 详情", "获取指定 issue 的详细信息",
                        """
                                {"method":"GET","url":"https://api.github.com/repos/{owner}/{repo}/issues/{number}","bodyMode":"json","params":{"owner":{"in":"path","type":"string","required":true,"description":"仓库 owner"},"repo":{"in":"path","type":"string","required":true,"description":"仓库名"},"number":{"in":"path","type":"integer","required":true,"description":"Issue 编号"}},"preconditions":["已认证"],"effects":["获取 Issue 详情"]}""", 3),
                tool("list_pull_requests", "列出 Pull Request", "获取仓库的 PR 列表",
                        """
                                {"method":"GET","url":"https://api.github.com/repos/{owner}/{repo}/pulls","bodyMode":"json","params":{"owner":{"in":"path","type":"string","required":true},"repo":{"in":"path","type":"string","required":true},"state":{"in":"query","type":"string","defaultValue":"open","exposeToLlm":false,"description":"open/closed/all"},"sort":{"in":"query","type":"string","defaultValue":"created","exposeToLlm":false},"direction":{"in":"query","type":"string","defaultValue":"desc","exposeToLlm":false}},"preconditions":["已认证"],"effects":["列出 PR 列表"]}""", 4),
                tool("search_code", "搜索代码", "在 GitHub 全平台或指定仓库中搜索代码片段",
                        """
                                {"method":"GET","url":"https://api.github.com/search/code","bodyMode":"json","params":{"q":{"in":"query","type":"string","required":true,"description":"搜索词，支持 q=keyword repo:owner/repo language:java 等"},"per_page":{"in":"query","type":"integer","defaultValue":"10","exposeToLlm":false},"page":{"in":"query","type":"integer","defaultValue":"1","exposeToLlm":false}},"preconditions":["已认证","有搜索权限"],"effects":["返回代码搜索结果"]}""", 5),
                tool("list_repos", "列出组织仓库", "列出指定用户或组织的公开仓库",
                        """
                                {"method":"GET","url":"https://api.github.com/users/{username}/repos","bodyMode":"json","params":{"username":{"in":"path","type":"string","required":true,"description":"用户名或组织名"},"type":{"in":"query","type":"string","defaultValue":"owner","exposeToLlm":false,"description":"all/owner/member"},"per_page":{"in":"query","type":"integer","defaultValue":"30","exposeToLlm":false}},"preconditions":["已认证"],"effects":["列出仓库列表"]}""", 6)
        ));
    }


    // ==================== Gmail API ====================

    private void seedGmailTemplate() {
        McpServiceEntity svc = upsertService("gmail", "Gmail 邮件",
                "通过 Gmail API 接收/发送/回复邮件。\n需要在 headers 中填写 Google OAuth2 Access Token。\n建议权限：https://www.googleapis.com/auth/gmail.modify",
                """
                {"Authorization":"Bearer YOUR_GMAIL_ACCESS_TOKEN","Content-Type":"application/json"}""",
                "HTTP_TOOL");
        replaceTools(svc, List.of(
                tool("list_messages", "列出邮件", "搜索/列出 Gmail 邮件（支持关键字、发件人、标签、未读筛选）",
                        """
                        {"method":"GET","url":"https://gmail.googleapis.com/gmail/v1/users/me/messages","bodyMode":"json","params":{"q":{"in":"query","type":"string","description":"Gmail 搜索语法，如 from:xxx@example.com is:unread subject:报告"},"maxResults":{"in":"query","type":"integer","defaultValue":"20","exposeToLlm":false,"description":"返回数量 1-500"},"pageToken":{"in":"query","type":"string","description":"分页 Token"}},"preconditions":["已授权 Gmail API"],"effects":["返回邮件列表"]}""", 1),
                tool("get_message", "读取邮件", "获取 Gmail 邮件详情（含正文、附件元数据）",
                        """
                        {"method":"GET","url":"https://gmail.googleapis.com/gmail/v1/users/me/messages/{id}","bodyMode":"json","params":{"id":{"in":"path","type":"string","required":true,"description":"邮件 ID"},"format":{"in":"query","type":"string","defaultValue":"full","exposeToLlm":false,"description":"full/metadata/minimal/raw"},"metadataHeaders":{"in":"query","type":"array","description":"metadata 格式时需要的 header"}},"preconditions":["已授权 Gmail API"],"effects":["返回邮件详情"]}""", 2),
                tool("send_email", "发送邮件", "通过 Gmail 发送新邮件（支持正文、主题、收件人）",
                        """
                        {"method":"POST","url":"https://gmail.googleapis.com/gmail/v1/users/me/messages/send","bodyMode":"json","params":{"to":{"in":"body","type":"array","required":true,"description":"收件人邮箱数组"},"subject":{"in":"body","type":"string","required":true,"description":"邮件主题"},"body":{"in":"body","type":"string","required":true,"description":"邮件正文（纯文本或 HTML）"},"cc":{"in":"body","type":"array","description":"抄送"},"bcc":{"in":"body","type":"array","description":"密送"},"attachments":{"in":"body","type":"array","description":"附件，格式：[{\\"filename\\":\\"a.pdf\\",\\"contentBase64\\":\\"...\\"}]"}},"preconditions":["已授权 Gmail API"],"effects":["邮件已发送"]}""", 3),
                tool("reply_email", "回复邮件", "回复指定 Gmail 邮件（自动带上原始主题和 Thread）",
                        """
                        {"method":"POST","url":"https://gmail.googleapis.com/gmail/v1/users/me/messages/send","bodyMode":"json","params":{"threadId":{"in":"body","type":"string","required":true,"description":"要回复的 Thread ID"},"to":{"in":"body","type":"array","required":true,"description":"回复收件人"},"subject":{"in":"body","type":"string","required":true,"description":"回复主题（通常 Re: ...）"},"body":{"in":"body","type":"string","required":true,"description":"回复正文"},"quoteOriginal":{"in":"body","type":"boolean","description":"是否引用原邮件正文"}},"preconditions":["已授权 Gmail API","存在原邮件 Thread"],"effects":["回复邮件已发送"]}""", 4)
        ));
    }

    // ==================== Microsoft Outlook / Graph ====================

    private void seedOutlookTemplate() {
        McpServiceEntity svc = upsertService("outlook", "Outlook / Microsoft 365",
                "通过 Microsoft Graph API 接收/发送/回复邮件。\n需要在 headers 中填写 OAuth2 Access Token。\n建议权限：Mail.ReadWrite、Mail.Send",
                """
                {"Authorization":"Bearer YOUR_OUTLOOK_ACCESS_TOKEN","Content-Type":"application/json"}""",
                "HTTP_TOOL");
        replaceTools(svc, List.of(
                tool("list_messages", "列出邮件", "列出当前用户的邮件（支持筛选、排序、分页）",
                        """
                        {"method":"GET","url":"https://graph.microsoft.com/v1.0/me/messages","bodyMode":"json","params":{"$top":{"in":"query","type":"integer","defaultValue":"20","exposeToLlm":false,"description":"返回数量"},"$skip":{"in":"query","type":"integer","defaultValue":"0","exposeToLlm":false,"description":"跳过数量"},"$filter":{"in":"query","type":"string","description":"OData 过滤，如 isRead eq false"},"$orderby":{"in":"query","type":"string","defaultValue":"receivedDateTime desc","exposeToLlm":false,"description":"排序"},"$search":{"in":"query","type":"string","description":"搜索关键字"}},"preconditions":["已授权 Microsoft Graph"],"effects":["返回邮件列表"]}""", 1),
                tool("get_message", "读取邮件", "获取邮件详情",
                        """
                        {"method":"GET","url":"https://graph.microsoft.com/v1.0/me/messages/{id}","bodyMode":"json","params":{"id":{"in":"path","type":"string","required":true,"description":"邮件 ID"}},"preconditions":["已授权 Microsoft Graph"],"effects":["返回邮件详情"]}""", 2),
                tool("send_mail", "发送邮件", "通过 Outlook 发送新邮件",
                        """
                        {"method":"POST","url":"https://graph.microsoft.com/v1.0/me/sendMail","bodyMode":"json","params":{"subject":{"in":"body","type":"string","required":true,"description":"主题"},"to":{"in":"body","type":"array","required":true,"description":"收件人数组，如 [{\\"emailAddress\\":{\\"address\\":\\"a@b.com\\"}}]"},"body":{"in":"body","type":"string","required":true,"description":"正文"},"bodyType":{"in":"body","type":"string","defaultValue":"html","description":"Text/HTML"},"cc":{"in":"body","type":"array","description":"抄送"},"bcc":{"in":"body","type":"array","description":"密送"},"attachments":{"in":"body","type":"array","description":"附件"}},"preconditions":["已授权 Mail.Send"],"effects":["邮件已发送"]}""", 3),
                tool("reply_mail", "回复邮件", "回复指定 Outlook 邮件",
                        """
                        {"method":"POST","url":"https://graph.microsoft.com/v1.0/me/messages/{id}/reply","bodyMode":"json","params":{"id":{"in":"path","type":"string","required":true,"description":"要回复的邮件 ID"},"comment":{"in":"body","type":"string","description":"回复正文"},"message":{"in":"body","type":"object","description":"可选：自定义回复消息对象（toRecipients、subject、body 等）"},"saveToSentItems":{"in":"body","type":"boolean","defaultValue":true,"description":"是否保存到已发送"}},"preconditions":["已授权 Mail.Send"],"effects":["回复邮件已发送"]}""", 4)
        ));
    }

    // ==================== Email (Resend) ====================

    private void seedEmailTemplate() {
        McpServiceEntity svc = upsertService("email-smtp", "邮件发送",
                "通过 Resend API 发送邮件（支持 HTML、纯文本、附件）。"
                        + "\n需要在 headers 中填写 Resend API Key（在 https://resend.com/api-keys 创建）。",
                """
                        {"Authorization":"Bearer YOUR_RESEND_API_KEY"}""",
                "HTTP_TOOL");
        replaceTools(svc, List.of(
                tool("send_email", "发送邮件", "发送一封邮件（支持 HTML 和纯文本）",
                        """
                                {"method":"POST","url":"https://api.resend.com/emails","bodyMode":"json","params":{"from":{"in":"body","type":"string","required":true,"description":"发件人格式：'Name <email@domain.com>'（域名需在 Resend 验证过）"},"to":{"in":"body","type":"array","required":true,"description":"收件人邮箱数组，如 ['a@b.com']"},"subject":{"in":"body","type":"string","required":true,"description":"邮件主题"},"html":{"in":"body","type":"string","description":"HTML 正文（与 text 二选一）"},"text":{"in":"body","type":"string","description":"纯文本正文（与 html 二选一）"},"cc":{"in":"body","type":"array","description":"抄送邮箱数组"},"bcc":{"in":"body","type":"array","description":"密送邮箱数组"},"reply_to":{"in":"body","type":"string","description":"回复地址"}},"preconditions":["已配置 Resend API Key","发件域名已验证"],"effects":["邮件已发送"]}""", 1)
        ));
    }

    // ==================== 钉钉 ====================

    private void seedDingtalkTemplate() {
        McpServiceEntity svc = upsertService("dingtalk", "钉钉机器人",
                "通过钉钉自定义机器人 Webhook 发送群消息。"
                        + "\n需要在 Webhook URL 中替换 access_token。"
                        + "\n安全设置如选择「加签」，需在 headers 中加 X-Dingtalk-Sign。",
                """
                        {"Content-Type":"application/json"}""",
                "HTTP_TOOL");
        replaceTools(svc, List.of(
                tool("send_group_message", "发送群消息", "通过钉钉机器人 Webhook 发送消息到群聊（支持 text/markdown/link/actionCard）",
                        """
                                {"method":"POST","url":"https://oapi.dingtalk.com/robot/send?access_token={access_token}","bodyMode":"json","params":{"access_token":{"in":"query","type":"string","required":true,"description":"Webhook access_token（在钉钉群设置-智能群助手-自定义机器人里获取）"},"msgtype":{"in":"body","type":"string","required":true,"description":"text/markdown/link/actionCard"},"content":{"in":"body","type":"object","description":"text 类型消息体：{\"content\": \"消息内容\"}"},"markdown":{"in":"body","type":"object","description":"markdown 类型：{\"title\": \"标题\", \"text\": \"内容\"}"},"at":{"in":"body","type":"object","description":"@人员：{\"atMobiles\": [\"138xxxx\"], \"isAtAll\": false}"}},"preconditions":["机器人已添加到群","access_token 有效"],"effects":["钉钉群消息已发送"]}""", 1)
        ));
    }

    // ==================== 飞书 ====================

    private void seedFeishuTemplate() {
        McpServiceEntity svc = upsertService("feishu", "飞书/Lark",
                "发送飞书消息、操作文档、日历等。"
                        + "\n需要在 headers 中填入 tenant_access_token。"
                        + "\n获取方式：POST https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal 传 app_id/app_secret。",
                """
                        {"Authorization":"Bearer YOUR_FEISHU_TENANT_ACCESS_TOKEN","Content-Type":"application/json"}""",
                "HTTP_TOOL");
        replaceTools(svc, List.of(
                tool("send_message", "发送飞书消息", "向指定 chat_id 或 open_id 发送消息（支持 text/post/interactive）",
                        """
                                {"method":"POST","url":"https://open.feishu.cn/open-apis/im/v1/messages","bodyMode":"json","params":{"receive_id_type":{"in":"query","type":"string","required":true,"description":"chat_id（群）/ open_id（个人）/ user_id（内部）/ union_id"},"receive_id":{"in":"body","type":"string","required":true,"description":"接收者 ID，根据 receive_id_type 决定"},"msg_type":{"in":"body","type":"string","required":true,"description":"text/post/interactive/image/file 等"},"content":{"in":"body","type":"string","required":true,"description":"消息内容 JSON 字符串，如 text 类型：{\\"text\\":\\"hello\\"}"}},"preconditions":["已获取 tenant_access_token","应用已开通消息权限"],"effects":["飞书消息已发送"]}""", 1),
                tool("get_token", "获取 tenant_access_token", "通过 app_id 和 app_secret 获取 tenant_access_token（有效期约 2 小时）",
                        """
                                {"method":"POST","url":"https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal","bodyMode":"json","params":{"app_id":{"in":"body","type":"string","required":true,"description":"应用 App ID"},"app_secret":{"in":"body","type":"string","required":true,"description":"应用 App Secret"}}},"preconditions":["已创建飞书自建应用"],"effects":["返回 tenant_access_token"]}""", 2)
        ));
    }

    // ==================== 企业微信 ====================

    private void seedWecomTemplate() {
        McpServiceEntity svc = upsertService("wecom", "企业微信",
                "发送企业微信群机器人消息 / 调用企业微信管理 API。"
                        + "\n群机器人：只需 Webhook Key。"
                        + "\n管理 API：需要 access_token（通过 corpid/corpsecret 换取）。",
                """
                        {"Content-Type":"application/json"}""",
                "HTTP_TOOL");
        replaceTools(svc, List.of(
                tool("send_webhook", "发送群机器人消息", "通过群机器人 Webhook 发送消息（支持 text/markdown/image/news）",
                        """
                                {"method":"POST","url":"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key={key}","bodyMode":"json","params":{"key":{"in":"query","type":"string","required":true,"description":"Webhook Key（群设置-群机器人-添加机器人后获得）"},"msgtype":{"in":"body","type":"string","required":true,"description":"text/markdown/image/news"},"text":{"in":"body","type":"object","description":"text 类型：{\"content\": \"内容\"}"},"markdown":{"in":"body","type":"object","description":"markdown 类型：{\"content\": \"### 标题\"}"},"news":{"in":"body","type":"object","description":"图文类型：{\"articles\": [{...}]}"}},"preconditions":["机器人已添加到群"],"effects":["企业微信群消息已发送"]}""", 1),
                tool("get_access_token", "获取 access_token", "通过 corpid 和 corpsecret 获取企业微信 API 的 access_token",
                        """
                                {"method":"GET","url":"https://qyapi.weixin.qq.com/cgi-bin/gettoken","bodyMode":"json","params":{"corpid":{"in":"query","type":"string","required":true,"description":"企业 ID（在管理后台-我的企业里）"},"corpsecret":{"in":"query","type":"string","required":true,"description":"应用 Secret（应用管理-自建应用里）"}},"preconditions":["已创建自建应用"],"effects":["返回 access_token"]}""", 2)
        ));
    }

    // ==================== Jira ====================

    private void seedJiraTemplate() {
        McpServiceEntity svc = upsertService("jira", "Jira 集成",
                "操作 Jira Cloud（创建/查询 Issue、管理 Sprint、搜索）。"
                        + "\n需要在 headers 中填写 Basic Auth："
                        + "\n  Base64(email:api_token)，例如 Authorization: Basic Zm9vQGJhci5jb206YXBpX3Rva2Vu",
                """
                        {"Authorization":"Basic BASE64_EMAIL_COLON_TOKEN","Content-Type":"application/json"}""",
                "HTTP_TOOL");
        replaceTools(svc, List.of(
                tool("search_issues", "JQL 搜索 Issue", "使用 JQL 语法搜索 Jira Issue",
                        """
                                {"method":"POST","url":"https://{your-domain}.atlassian.net/rest/api/3/search","bodyMode":"json","params":{"your-domain":{"in":"path","type":"string","required":true,"description":"你的 Jira 域名（不含 .atlassian.net）"},"jql":{"in":"body","type":"string","required":true,"description":"JQL 搜索语句，如 project = PROJ AND status = Open"},"maxResults":{"in":"body","type":"integer","defaultValue":50,"exposeToLlm":false},"startAt":{"in":"body","type":"integer","defaultValue":0,"exposeToLlm":false}},"preconditions":["已认证","有 Jira 访问权限"],"effects":["返回 Issue 搜索结果"]}""", 1),
                tool("create_issue", "创建 Jira Issue", "在指定项目创建一个新的 Issue",
                        """
                                {"method":"POST","url":"https://{your-domain}.atlassian.net/rest/api/3/issue","bodyMode":"json","params":{"your-domain":{"in":"path","type":"string","required":true,"description":"Jira 域名前缀"},"projectKey":{"in":"body","type":"string","required":true,"description":"项目 Key，如 PROJ"},"summary":{"in":"body","type":"string","required":true,"description":"Issue 标题"},"issueType":{"in":"body","type":"string","required":true,"description":"Bug / Task / Story"},"description":{"in":"body","type":"string","description":"Issue 正文（支持 Atlassian Document Format）"},"assignee":{"in":"body","type":"string","description":"负责人 accountId"},"labels":{"in":"body","type":"array","description":"标签数组"}},"preconditions":["已认证","有项目写权限"],"effects":["创建了 Jira Issue"]}""", 2)
        ));
    }
    // ==================== 真实 MCP Server（STDIO） ====================

    private void seedStdioTemplates() {
        upsertStdioService("filesystem-mcp", "文件系统 MCP",
                "官方 Filesystem MCP Server：读写本地文件、目录遍历、搜索。\n连接后会自动加载 read_file / write_file / list_directory 等工具。\n路径参数请替换为实际工作区目录。",
                "npx", List.of("-y", "@modelcontextprotocol/server-filesystem", "F:/workspace"), "{}");

        upsertStdioService("github-mcp", "GitHub MCP",
                "官方 GitHub MCP Server：管理 Issue/PR、仓库、代码搜索。\n需要设置 GITHUB_PERSONAL_ACCESS_TOKEN 环境变量。",
                "npx", List.of("-y", "@modelcontextprotocol/server-github"),
                "{\"GITHUB_PERSONAL_ACCESS_TOKEN\":\"${GITHUB_PERSONAL_ACCESS_TOKEN}\"}");

        upsertStdioService("git-mcp", "Git MCP",
                "Git 操作 MCP Server：提交、分支、日志、Diff 等。",
                "npx", List.of("-y", "@modelcontextprotocol/server-git"), "{}");

        upsertStdioService("fetch-mcp", "网页抓取 MCP",
                "Fetch MCP Server：抓取网页内容、转换 Markdown。",
                "npx", List.of("-y", "@modelcontextprotocol/server-fetch"), "{}");

        upsertStdioService("memory-mcp", "记忆 MCP",
                "Memory MCP Server：知识图谱持久化记忆，适合长期记忆场景。",
                "npx", List.of("-y", "@modelcontextprotocol/server-memory"), "{}");

        upsertStdioService("sequential-thinking-mcp", "顺序思考 MCP",
                "Sequential Thinking MCP Server：帮助模型进行结构化、渐进式推理。",
                "npx", List.of("-y", "@modelcontextprotocol/server-sequential-thinking"), "{}");

        upsertStdioService("brave-search-mcp", "Brave 搜索 MCP",
                "Brave Search MCP Server：联网搜索。\n需要设置 BRAVE_API_KEY 环境变量。",
                "npx", List.of("-y", "@modelcontextprotocol/server-brave-search"),
                "{\"BRAVE_API_KEY\":\"${BRAVE_API_KEY}\"}");
    }

    private void upsertStdioService(String name, String displayName, String description,
                                    String command, List<String> args, String env) {
        Optional<McpServiceEntity> opt = mcpRepo.findByNameAndScope(name, "SYSTEM");
        McpServiceEntity svc;
        if (opt.isPresent()) {
            svc = opt.get();
            boolean changed = false;
            if (svc.getDescription() == null || !svc.getDescription().contains(description.split("\\n")[0])) {
                svc.setDescription("【内置模板】" + displayName + " - " + description);
                changed = true;
            }
            if (!"STDIO".equalsIgnoreCase(svc.resolveTransport())) {
                svc.setTransport("STDIO");
                changed = true;
            }
            if (svc.getCommand() == null || !svc.getCommand().equals(command)) {
                svc.setCommand(command);
                changed = true;
            }
            if (svc.getArgs() == null || !svc.getArgs().equals(toJson(args))) {
                svc.setArgs(toJson(args));
                changed = true;
            }
            if (svc.getEnv() == null || !svc.getEnv().equals(env)) {
                svc.setEnv(env);
                changed = true;
            }
            if (svc.getIsTemplate() == null || !svc.getIsTemplate()) {
                svc.setIsTemplate(true);
                changed = true;
            }
            if (changed) {
                svc = mcpRepo.save(svc);
                log.info("[SystemDataSeeder] 更新 MCP STDIO 模板: {}", name);
            }
        } else {
            svc = McpServiceEntity.builder()
                    .name(name)
                    .description("【内置模板】" + displayName + " - " + description)
                    .transport("STDIO")
                    .command(command)
                    .args(toJson(args))
                    .env(env)
                    .scope("SYSTEM")
                    .isTemplate(true)
                    .isConnected(false)
                    .build();
            svc = mcpRepo.save(svc);
            log.info("[SystemDataSeeder] 新建 MCP STDIO 模板: {}", name);
        }
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return value == null ? "{}" : String.valueOf(value);
        }
    }


    // ==================== 辅助方法 ====================

    private McpServiceEntity upsertService(String name, String displayName, String description,
                                           String headers, String transport) {
        Optional<McpServiceEntity> opt = mcpRepo.findByNameAndScope(name, "SYSTEM");
        McpServiceEntity svc;
        if (opt.isPresent()) {
            svc = opt.get();
            boolean changed = false;
            if (svc.getDescription() == null || !svc.getDescription().contains(description.split("\\n")[0])) {
                svc.setDescription("【内置模板】" + displayName + " - " + description);
                changed = true;
            }
            if (svc.getHeaders() == null || svc.getHeaders().isBlank()) {
                svc.setHeaders(headers);
                changed = true;
            }
            if (!"HTTP_TOOL".equalsIgnoreCase(svc.resolveTransport())) {
                svc.setTransport(transport);
                changed = true;
            }
            if (svc.getIsTemplate() == null || !svc.getIsTemplate()) {
                svc.setIsTemplate(true);
                changed = true;
            }
            if (changed) {
                svc = mcpRepo.save(svc);
                log.info("[SystemDataSeeder] 更新 MCP 模板: {}", name);
            }
        } else {
            svc = McpServiceEntity.builder()
                    .name(name)
                    .description("【内置模板】" + displayName + " - " + description)
                    .transport(transport)
                    .headers(headers)
                    .scope("SYSTEM")
                    .isTemplate(true)
                    .isConnected(false)
                    .build();
            svc = mcpRepo.save(svc);
            log.info("[SystemDataSeeder] 新建 MCP 模板: {}", name);
        }
        return svc;
    }

    private void replaceTools(McpServiceEntity service, List<ToolDef> tools) {
        List<McpToolEntity> existing = toolRepo.findByServiceIdOrderBySortOrderAsc(service.getId());
        // 按 toolName 建索引
        java.util.Map<String, McpToolEntity> byName = new java.util.HashMap<>();
        for (McpToolEntity t : existing) byName.put(t.getToolName(), t);

        for (ToolDef def : tools) {
            McpToolEntity t = byName.get(def.name());
            if (t != null) {
                boolean changed = false;
                if (def.displayName() != null && !def.displayName().equals(t.getDisplayName())) {
                    t.setDisplayName(def.displayName());
                    changed = true;
                }
                if (def.description() != null && !def.description().equals(t.getDescription())) {
                    t.setDescription(def.description());
                    changed = true;
                }
                if (def.config() != null && !def.config().equals(t.getToolConfig())) {
                    t.setToolConfig(def.config());
                    changed = true;
                }
                if (def.sortOrder() != t.getSortOrder()) {
                    t.setSortOrder(def.sortOrder());
                    changed = true;
                }
                if (changed) toolRepo.save(t);
            } else {
                toolRepo.save(McpToolEntity.builder()
                        .service(service)
                        .toolName(def.name())
                        .displayName(def.displayName())
                        .description(def.description())
                        .toolConfig(def.config())
                        .enabled(true)
                        .sortOrder(def.sortOrder())
                        .build());
            }
        }
        // 不再存在的旧 tool 删除（只删 SYSTEM scope 下的）
        java.util.Set<String> keepNames = tools.stream().map(ToolDef::name).collect(java.util.stream.Collectors.toSet());
        for (McpToolEntity t : existing) {
            if (!keepNames.contains(t.getToolName())) {
                toolRepo.delete(t);
                log.info("[SystemDataSeeder] 删除旧 Tool: {}.{}", service.getName(), t.getToolName());
            }
        }
    }

    private ToolDef tool(String name, String displayName, String description, String config, int sortOrder) {
        // config 是 JSON 字符串，验证一下
        try {
            MAPPER.readValue(config, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("[SystemDataSeeder] Tool {} 的 config JSON 无效: {}", name, e.getMessage());
        }
        return new ToolDef(name, displayName, description, config, sortOrder);
    }

    private record ToolDef(String name, String displayName, String description, String config, int sortOrder) {
    }
}
