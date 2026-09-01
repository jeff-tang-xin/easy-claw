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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * 播种内置子 Agent 声明。
     * <p>
     * 用 frontmatter 的 {@code seedVersion} 标记模板版本，实现「可升级但不覆盖用户修改」：
     * <ul>
     *   <li>文件不存在 → 写入当前版本</li>
     *   <li>文件存在且 {@code seedVersion} 等于历史内置版本 → 用户没改过，刷新为当前版本</li>
     *   <li>文件存在但无 {@code seedVersion} 或版本更高 → 视为用户自定义，保留不动</li>
     * </ul>
     * 这解决了旧实现「{@code if (exists) return;} 导致内置模板改了也永不生效」的漂移问题。
     * <p>
     * 刻意不写 {@code steps}：迭代上限由 {@code agentscope.agent.subagent-steps} 统一管控，
     * 避免散落在各 md 文件里需要手工同步（历史上工作区那份 {@code steps: 8} 就是这么失控的）。
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

    /** 内置子 Agent 模板的当前版本。修改任一内置声明正文时递增，使旧的未改动文件自动刷新。 */
    static final int SUBAGENT_SEED_VERSION = 4;

    /** frontmatter 扫描行数上限：正常声明的头部远小于此值，防止无闭合分隔符时扫全文。 */
    private static final int MAX_FRONTMATTER_LINES = 50;

    /** frontmatter 中 seedVersion 的宽松匹配：容忍额外空格、引号、行尾注释。 */
    private static final Pattern SEED_VERSION_PATTERN =
            Pattern.compile("^seedVersion\\s*:\\s*\"?(\\d{1,9})\"?\\s*(?:#.*)?$");

    /** frontmatter 中 seedHash 的匹配（内置模板正文指纹，用于识别用户是否改动过）。 */
    private static final Pattern SEED_HASH_PATTERN =
            Pattern.compile("^seedHash\\s*:\\s*\"?([0-9a-f]{8,64})\"?\\s*(?:#.*)?$");

    /**
     * 判断一份已存在的声明文件是否应被内置模板覆盖。
     * <p>
     * 严格语义：**只有确认用户从未改动过**才允许刷新。判据是磁盘正文指纹与该文件自称的
     * seedHash 一致；一旦用户编辑过正文，指纹不匹配，文件即被永久视为用户资产。
     * <p>
     * 抽成静态纯函数便于单测。
     *
     * @param existing 已存在文件的解析结果，null 表示无法解析（视为用户自定义）
     * @return true 表示可安全刷新为当前内置模板
     */
    static boolean shouldOverwrite(SeedMeta existing) {
        if (existing == null || existing.version() == null) {
            // 无版本标记 → 用户手写或旧版遗留，保守不动
            return false;
        }
        int v = existing.version();
        // 范围判断代替手工维护的白名单集合：避免升版时忘记登记导致文件永久不再刷新
        if (v < 1 || v > SUBAGENT_SEED_VERSION) {
            // 未来版本或非法值 → 不回退覆盖
            return false;
        }
        if (v >= SUBAGENT_SEED_VERSION) {
            // 已是最新，无需写盘
            return false;
        }
        if (existing.hash() == null) {
            // 旧版模板尚未写入指纹，无法证明未被改动 → 保守保留
            return false;
        }
        // 仅当正文与「该版本原始模板」完全一致，才认定用户未改动过
        return existing.hash().equals(existing.actualHash());
    }

    /** 声明文件 frontmatter 的播种元数据。actualHash 为磁盘正文实际指纹。 */
    record SeedMeta(Integer version, String hash, String actualHash) {
    }

    /**
     * 播种一份内置子 Agent 声明。
     * <p>
     * frontmatter 自动写入 {@code role: <name>} —— 内置声明的文件名与
     * {@code DataInitializer} 的种子角色同名，二者本就是同一实体的两个面：
     * 角色（DB）定义「你是什么」（人格 + 模型），声明（.md）定义「你怎么干活」
     * （tools / steps / 工作区模式）。绑定后 {@code SubagentLoader} 会把角色人格
     * 前置到正文，使子 Agent 真正按角色设定运行。
     */
    private void seedSubagent(Path dir, String name, String description, String prompt) {
        Path file = dir.resolve(name + ".md");
        String body = prompt + "\n";
        String content = "---\ndescription: " + description
                + "\nrole: " + name
                + "\nseedVersion: " + SUBAGENT_SEED_VERSION
                + "\nseedHash: " + bodyHash(body)
                + "\n---\n\n" + body;
        try {
            if (Files.exists(file)) {
                SeedMeta existing = readSeedMeta(file);
                if (!shouldOverwrite(existing)) {
                    return;
                }
                writeAtomically(file, content);
                log.info("内置子 Agent [{}] 模板由 v{} 升级到 v{}", name, existing.version(), SUBAGENT_SEED_VERSION);
                return;
            }
            writeAtomically(file, content);
            log.info("播种内置子 Agent: {}", name);
        } catch (Exception e) {
            log.warn("播种子 Agent {} 失败: {}", name, e.getMessage());
        }
    }

    /** 正文指纹：SHA-256 前 16 位十六进制，足够区分且不臃肿 frontmatter。 */
    static String bodyHash(String body) {
        try {
            // 归一化换行，避免 CRLF/LF 差异被误判为「用户改动过」
            String normalized = body.replace("\r\n", "\n").replace("\r", "\n");
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 属 JDK 必备算法，理论不可达
            throw new IllegalStateException("计算模板指纹失败", e);
        }
    }

    /** 原子写入：先写临时文件再 move，避免中途崩溃留下截断的声明文件。 */
    private static void writeAtomically(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 部分文件系统不支持原子移动，退化为普通替换
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 解析声明文件 frontmatter 中的播种元数据。
     * <p>
     * 一次性读取全文：既算正文指纹，也取 seedVersion / seedHash，避免重复 I/O。
     * 任何解析失败都返回 null（保守视为用户自定义，绝不覆盖）。
     */
    static SeedMeta readSeedMeta(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            // 剥离 UTF-8 BOM，否则首行不等于 "---" 会误判为无 frontmatter
            if (!raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
                raw = raw.substring(1);
            }
            String[] lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
            if (lines.length == 0 || !lines[0].trim().equals("---")) {
                return null;
            }
            Integer version = null;
            String hash = null;
            int closeIdx = -1;
            // 只扫描 frontmatter 区，且限制行数，避免无闭合分隔符时扫全文
            int limit = Math.min(lines.length, MAX_FRONTMATTER_LINES);
            for (int i = 1; i < limit; i++) {
                String l = lines[i].trim();
                if (l.equals("---")) {
                    closeIdx = i;
                    break;
                }
                Matcher mv = SEED_VERSION_PATTERN.matcher(l);
                if (mv.matches()) {
                    version = Integer.valueOf(mv.group(1));
                    continue;
                }
                Matcher mh = SEED_HASH_PATTERN.matcher(l);
                if (mh.matches()) {
                    hash = mh.group(1);
                }
            }
            if (closeIdx < 0) {
                // frontmatter 未闭合 → 结构不可信，视为用户自定义
                return null;
            }
            // 正文 = 闭合分隔符之后，跳过紧随的空行（播种时写入的是 "---\n\n" + body）
            int start = closeIdx + 1;
            while (start < lines.length && lines[start].isEmpty()) {
                start++;
            }
            String body = String.join("\n", Arrays.asList(lines).subList(start, lines.length));
            return new SeedMeta(version, hash, bodyHash(body));
        } catch (Exception e) {
            log.debug("解析播种元数据失败 {}: {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * 播种内置场景。
     * <p>
     * <b>回写语义</b>：内置场景（{@code builtin=true}）每次启动都按最新定义回写，
     * 等同于"第一次播种"。原因是场景语义已重新定义为「环境 + 能力边界 + 方法论」，
     * 老库里按旧定义写的内容（把基座的任务闭环重抄一遍）留着会与基座打架。
     * <p>
     * <b>不回写的情况</b>：用户把内置场景改成了自定义（{@code builtin=false}），
     * 视为用户资产，只跳过不覆盖。用户新建的场景本就不在此列。
     * <p>
     * 代价：用户直接改过内置场景正文的修改会在下次启动被覆盖。选择接受，因为
     * 内置场景定位是"平台提供的标准方法论"，需要随平台演进；用户要定制应另存为
     * 新场景。
     */
    private void seedScenarios() {
        ensureColumn("scenarios", "mode", "TEXT NOT NULL DEFAULT 'single'");
        upsertScenario("general-coding", "通用编程", "💻",
                "你在一个真实的软件工程项目里工作，面对的是已有代码、已有约定和已有历史包袱，"
                        + "而不是从零起步的玩具工程。改动会直接落到用户的代码库上。",
                "single", """
                        方法论：单智能体闭环——由你独立完成从理解到验证的全过程，不做任务分发。
                        1. 先建立事实：读相关代码、确认现有结构与约定，不凭猜测动手。
                        2. 遵循既有风格：命名、分层、错误处理沿用项目现状，不引入个人偏好的新范式。
                        3. 外科手术式修改：只改必要的地方，不顺手重构无关代码。
                        4. 方案有取舍时先说明选项与理由，再按最优方案实现。
                        5. 改完必须自测（编译/运行/关键路径检查），确认可用再汇报。
                        6. 汇报格式：做了什么 / 关键改动 / 如何验证 / 遗留风险。""", null);

        upsertScenario("team-dev", "团队协作开发", "🤝",
                "你在一个有分工的开发团队里担任编排者，手下有可调度的专项成员。"
                        + "任务规模超出单人一次性完成的范围，需要拆解、并行与汇总。",
                "team", """
                        方法论：多智能体协作——你是编排者（Orchestrator），负责任务分发、进度把控与最终汇总。
                        - 专项工作交给子 Agent，不要亲自重复成员已做的事。
                        - 你对最终交付负责：成员产出不合格时就地修正或自己补做，不原样转发。
                        - 汇总时交叉验证成员结论，冲突之处必须查证后给出唯一答案。""", """
                        {"steps":[
                          {"subagent":"planner","instruction":"分析需求并输出子任务清单（含验收标准与依赖关系）","parallel":false},
                          {"subagent":"coder","instruction":"按规划清单完成代码实现，自测通过后汇报改动","parallel":false},
                          {"subagent":"reviewer","instruction":"评审 coder 的改动：对照验收标准逐条核对，输出问题清单与结论","parallel":true}
                        ]}""");

        upsertScenario("code-review", "代码评审会", "🔍",
                "你在主持一场代码评审会，评审对象是已提交或待合入的改动。"
                        + "产出是评审结论，默认不直接改代码。",
                "team", """
                        方法论：多智能体协作——你是评审会主持人，组织评审、汇总意见、给出最终裁决。
                        - 评审意见必须落到具体文件与位置，可执行可验证，不停留在"建议优化"。
                        - 按严重程度分级（阻断 / 应改 / 建议），让用户能据此决定是否合入。
                        - 成员意见分歧时由你查证裁决，不把分歧原样抛给用户。""", """
                        {"steps":[
                          {"subagent":"reviewer","instruction":"全面评审指定代码：正确性、可读性、缺陷、安全、性能，按严重程度输出问题清单","parallel":false},
                          {"subagent":"coder","instruction":"从可运行性角度复核：编译/依赖/调用链是否完整，指出无法落地的问题","parallel":true}
                        ]}""");
    }

    private void upsertScenario(String name, String displayName, String icon, String description,
                                String mode, String systemPrompt, String workflow) {
        Optional<ScenarioEntity> existing = scenarioRepo.findByName(name);
        if (existing.isPresent()) {
            ScenarioEntity e = existing.get();
            if (!Boolean.TRUE.equals(e.getBuiltin())) {
                // 用户已把它转为自定义场景，属于用户资产，不覆盖
                return;
            }
            // 只回写「平台定义」的字段；active 是用户的启用选择，保持不动
            e.setDisplayName(displayName);
            e.setIcon(icon);
            e.setDescription(description);
            e.setMode(mode);
            e.setSystemPrompt(systemPrompt);
            e.setWorkflow(workflow);
            scenarioRepo.save(e);
            log.info("内置场景已回写为最新定义: {} ({})", displayName, mode);
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
