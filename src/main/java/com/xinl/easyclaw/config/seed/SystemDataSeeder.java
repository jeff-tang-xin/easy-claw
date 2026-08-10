package com.xinl.easyclaw.config.seed;

import com.xinl.easyclaw.mcp.entity.McpServiceEntity;
import com.xinl.easyclaw.mcp.repository.McpServiceRepository;
import com.xinl.easyclaw.skill.entity.SkillEntity;
import com.xinl.easyclaw.skill.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 系统内置数据播种器。
 * <p>
 * 每次启动都会执行 UPSERT，确保 SYSTEM 级别的内置 MCP 服务和 Skill 始终存在。
 * SYSTEM 级别条目 Service/Controller 层禁止修改和删除。
 */
@Component
public class SystemDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(SystemDataSeeder.class);

    private final McpServiceRepository mcpRepo;
    private final SkillRepository skillRepo;

    public SystemDataSeeder(McpServiceRepository mcpRepo, SkillRepository skillRepo) {
        this.mcpRepo = mcpRepo;
        this.skillRepo = skillRepo;
    }

    // ========== MCP 内置数据 ==========

    public void seedAll() {
        seedEmail();
        seedGit();
        seedDingtalk();
        seedFeishu();
        seedWecom();

        seedFrontendReviewSkill();
        seedBackendApiSkill();
        seedCodeRefactorSkill();
        seedDevOpsCISkill();
        seedSqlOptimizerSkill();
        seedWriteQualitySkill();

        log.info("系统内置数据播种完成");
    }

    private void seedEmail() {
        upsertMcp("email-smtp", "邮件发送", """
                通过 SMTP 协议发送邮件。
                配置前请在 headers 中填写 Authorization（如果用 API Key 模式）或在 env 中设置 SMTP_HOST/SMTP_USER/SMTP_PASS。
                默认使用 HTTP_TOOL 桥接方式，直接调用邮件服务商的 REST API（如 Resend、SendGrid、Mailgun）。
                如使用 SMTP 协议，建议自行安装 @modelcontextprotocol/server-email 后改为 STDIO 连接。""",
                """
                {"method":"POST","url":"https://api.resend.com/emails","bodyMode":"json","params":{"from":{"in":"body","type":"string","required":true,"description":"发件人，如 'Name <email@domain.com>'"},"to":{"in":"body","type":"array","required":true,"description":"收件人列表"},"subject":{"in":"body","type":"string","required":true,"description":"邮件主题"},"html":{"in":"body","type":"string","description":"HTML 正文"},"text":{"in":"body","type":"string","description":"纯文本正文"}}}""",
                """
                {"Authorization":"Bearer YOUR_RESEND_API_KEY"}""");
    }

    private void seedGit() {
        upsertMcp("github", "GitHub 集成", """
                操作 GitHub 仓库：创建 Issue/PR、查看代码、搜索、管理 Workflow。
                需要在 headers 中填写 GitHub Personal Access Token。
                也可改为 STDIO 模式连接官方 @modelcontextprotocol/server-github。""",
                """
                {"method":"GET","url":"https://api.github.com/repos/{owner}/{repo}/issues","params":{"owner":{"in":"path","type":"string","required":true,"description":"仓库 owner"},"repo":{"in":"path","type":"string","required":true,"description":"仓库名"},"state":{"in":"query","type":"string","description":"open/closed/all"}}}""",
                """
                {"Authorization":"Bearer YOUR_GITHUB_TOKEN","Accept":"application/vnd.github+json","X-GitHub-Api-Version":"2022-11-28"}""");
    }

    private void seedDingtalk() {
        upsertMcp("dingtalk", "钉钉机器人", """
                通过钉钉自定义机器人 Webhook 发送群消息。
                需要在 url 中替换 WEBHOOK_TOKEN 为你的机器人 Webhook 地址里的 access_token 参数值。
                消息类型支持 text/markdown/link/actionCard。""",
                """
                {"method":"POST","url":"https://oapi.dingtalk.com/robot/send?access_token={token}","bodyMode":"json","params":{"token":{"in":"query","type":"string","required":true,"description":"Webhook access_token"},"msgtype":{"in":"body","type":"string","required":true,"description":"消息类型: text/markdown/link"},"content":{"in":"body","type":"object","description":"消息内容对象"}}}""",
                """
                {"Content-Type":"application/json"}""");
    }

    private void seedFeishu() {
        upsertMcp("feishu", "飞书/Lark 集成", """
                发送飞书群消息、读取文档、操作日历。
                需要在 headers 中填入 tenant_access_token（通过 app_id/app_secret 换取）。
                建议先调用 auth.v3.tenant_access_token.internal 获取 token，再调用消息接口。""",
                """
                {"method":"POST","url":"https://open.feishu.cn/open-apis/im/v1/messages","bodyMode":"json","params":{"receive_id_type":{"in":"query","type":"string","required":true,"description":"chat_id/open_id/user_id"},"receive_id":{"in":"body","type":"string","required":true,"description":"接收者 ID"},"msg_type":{"in":"body","type":"string","required":true,"description":"text/post/image/file"},"content":{"in":"body","type":"object","required":true,"description":"消息内容 JSON"}}}""",
                """
                {"Authorization":"Bearer YOUR_FEISHU_TENANT_ACCESS_TOKEN","Content-Type":"application/json"}""");
    }

    private void seedWecom() {
        upsertMcp("wecom", "企业微信集成", """
                发送企业微信群机器人消息、调用企业微信 API。
                群机器人需要 Webhook Key；企业内部应用需要 access_token（通过 corpid/corpsecret 换取）。
                支持 text/markdown/image/news 等消息类型。""",
                """
                {"method":"POST","url":"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key={key}","bodyMode":"json","params":{"key":{"in":"query","type":"string","required":true,"description":"机器人 Webhook Key"},"msgtype":{"in":"body","type":"string","required":true,"description":"text/markdown/image/news"},"text":{"in":"body","type":"object","description":"msgtype=text 时的内容"},"markdown":{"in":"body","type":"object","description":"msgtype=markdown 时的内容"}}}""",
                """
                {"Content-Type":"application/json"}""");
    }

    private void upsertMcp(String name, String displayName, String description,
                           String implementationConfig, String headers) {
        Optional<McpServiceEntity> existing = mcpRepo.findByNameAndScope(name, "SYSTEM");
        if (existing.isPresent()) return;

        McpServiceEntity entity = McpServiceEntity.builder()
                .name(name)
                .description("【内置】" + displayName + " - " + description)
                .transport("HTTP_TOOL")
                .implementationConfig(implementationConfig)
                .headers(headers)
                .scope("SYSTEM")
                .isConnected(false)
                .build();
        mcpRepo.save(entity);
        log.info("播种内置 MCP: {}", name);
    }

    // ========== Skill 内置数据 ==========

    private void seedFrontendReviewSkill() {
        upsertSkill("frontend-review", "前端代码审查", """
                你是一位资深前端架构师，请按以下维度审查代码：

                1. 组件设计：单一职责、可复用性、props 接口清晰度
                2. 性能：虚拟列表、memo 滥用、重渲染、bundle 体积
                3. 可访问性：语义化 HTML、ARIA 标签、键盘导航
                4. 状态管理：状态提升是否合理、Context 粒度过粗、是否可以用组合式函数
                5. 样式：CSS-in-JS 过度、选择器嵌套、是否可以用 design token
                6. 工程化：TypeScript strict 模式、eslint/prettier 一致性、测试覆盖

                输出格式：
                - [严重] 必须修复的问题
                - [建议] 可优化的点
                - [亮点] 做得好的地方
                """);
    }

    private void seedBackendApiSkill() {
        upsertSkill("backend-api-design", "后端 API 设计", """
                你是一位后端 API 设计专家，请按 RESTful 最佳实践审查 API 设计：

                1. URL 规范：名词复数、层级不超过 3 层、避免动词
                2. HTTP Method：GET 查询 / POST 创建 / PUT 全量更新 / PATCH 部分更新 / DELETE 删除
                3. 响应码：200/201/204/400/401/403/404/409/422/500 正确使用
                4. 分页：cursor-based 优于 offset，提供 total/has_next/next_cursor
                5. 错误响应：统一 {code, message, details, requestId} 格式
                6. 版本：URL 路径版本 / Header 版本
                7. 幂等性：POST 用 Idempotency-Key，PUT/DELETE 天然幂等
                8. 安全：认证授权、输入校验、CORS、限流
                """);
    }

    private void seedCodeRefactorSkill() {
        upsertSkill("code-refactor", "代码重构", """
                你是一位擅长重构的软件工匠。重构时遵循以下原则：

                1. 先写测试再重构，确保行为不变
                2. 小步提交，每步都可编译可运行
                3. 消除重复（DRY）：提取公共方法/父类/函数
                4. 降低复杂度：圈复杂度 > 15 的函数必须拆分
                5. 改善命名：变量/函数名表达意图，避免缩写
                6. 单一职责：一个类只做一件事
                7. 开闭原则：对扩展开放，对修改关闭
                8. 依赖倒置：依赖抽象而非具体实现

                常用手法：
                - Extract Method / Inline Method
                - Move Field / Move Method
                - Replace Conditional with Polymorphism
                - Introduce Interface / Introduce Delegation
                """);
    }

    private void seedDevOpsCISkill() {
        upsertSkill("devops-ci", "DevOps 与 CI/CD", """
                你是一位 DevOps 工程师，熟悉现代 CI/CD 流水线设计：

                1. 流水线阶段：lint → test → build → package → deploy → smoke-test
                2. Docker 最佳实践：多阶段构建、.dockerignore、non-root 用户、healthcheck
                3. GitHub Actions / GitLab CI：缓存策略、并发控制、artifacts 传递
                4. 部署策略：蓝绿部署、金丝雀发布、rolling update
                5. 环境管理：dev/staging/prod 隔离，配置外置（env/secret manager）
                6. 可观测性：结构化日志（JSON）、metrics（Prometheus）、tracing（OpenTelemetry）
                7. 安全扫描：SAST（代码）、SCA（依赖）、DAST（运行时漏洞）
                """);
    }

    private void seedSqlOptimizerSkill() {
        upsertSkill("sql-optimizer", "SQL 优化", """
                你是一位数据库调优专家。分析 SQL 时关注：

                1. 执行计划：EXPLAIN / EXPLAIN ANALYZE，关注 type/key/rows/Extra 列
                2. 索引：覆盖索引、最左前缀原则、避免索引失效（函数/类型转换/隐式转换）
                3. JOIN：避免 SELECT *、小表驱动大表、检查 JOIN 条件是否走索引
                4. 子查询：IN → EXISTS / JOIN 改写、派生表是否被优化器合并
                5. 分页：深分页用游标 / 延迟关联替代 OFFSET
                6. 聚合：GROUP BY 是否走索引、是否可以预聚合（物化视图）
                7. 事务：避免长事务、合理隔离级别、死锁分析
                8. 反范式：适当冗余（缓存字段、计数表）换性能
                """);
    }

    private void seedWriteQualitySkill() {
        upsertSkill("write-quality", "技术文档与报告", """
                你擅长撰写高质量技术文档。遵循以下结构：

                1. 面向读者：明确目标读者（开发/产品/运维/管理者），调整深度
                2. 结构清晰：摘要 → 背景 → 方案 → 实现 → 测试 → 风险 → 排期
                3. 简洁准确：避免官话套话，每个段落有明确信息点
                4. 图示辅助：架构图（Mermaid）、时序图、流程图 > 千言万语
                5. 代码示例：最小可运行示例，标注关键行
                6. 术语一致：首次出现给出全称和缩写
                7. 可维护：重要决策记录 ADR（Architecture Decision Record）
                """);
    }

    private void upsertSkill(String name, String displayName, String promptFragment) {
        Optional<SkillEntity> existing = skillRepo.findByNameAndScope(name, "SYSTEM");
        if (existing.isPresent()) return;

        SkillEntity entity = SkillEntity.builder()
                .name(name)
                .displayName(displayName)
                .description("【内置】" + displayName)
                .scope("SYSTEM")
                .promptFragment(promptFragment)
                .enabled(true)
                .build();
        skillRepo.save(entity);
        log.info("播种内置 Skill: {}", name);
    }
}
