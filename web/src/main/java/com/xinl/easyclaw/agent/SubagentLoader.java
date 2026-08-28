package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.config.AgentScopeProperties;
import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import com.xinl.easyclaw.role.service.RoleManagementService;
import com.xinl.easyclaw.scenario.ScenarioBinding;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 子 Agent 声明加载器
 * <p>
 * 从 Workspace 的 {@code subagents} 目录加载子 Agent 声明文件
 * （{@code <workspace>/subagents/<agent_id>.md}，文件名即 agent_id），
 * 解析 YAML frontmatter（description/model/steps/tools/skills）与正文（系统提示词），
 * 构建 {@link SubagentDeclaration} 注册到主控 HarnessAgent。
 * <p>
 * 所有子 Agent 与主控共享同一个 Workspace，文件操作同样受沙箱限制。
 */
@Component
public class SubagentLoader {

    private static final Logger log = LoggerFactory.getLogger(SubagentLoader.class);

    private final RoleManagementService roleService;
    private final AgentScopeProperties properties;

    public SubagentLoader(RoleManagementService roleService, AgentScopeProperties properties) {
        this.roleService = roleService;
        this.properties = properties;
    }

    /**
     * 合并加载全局 + Workspace 两级子 Agent 声明（无场景绑定 = 不限制）。
     * 规则：workspace 级覆盖 global 级（同名时后者覆盖前者）。
     */
    public List<SubagentDeclaration> loadMerged(Path globalDir, Path workspaceDir) {
        return loadMerged(globalDir, workspaceDir, ScenarioBinding.EMPTY);
    }

    /**
     * 合并加载并施加场景绑定的<b>子 Agent 硬隔离</b>。
     * <p>
     * 隔离只作用于 skill 白名单，<b>不碰 tools</b>：MCP 硬隔离已在父 toolkit
     * 注册阶段完成（见 {@code AgentFactory.createWorkspaceToolkit(List)}），
     * 子 Agent 继承的副本天然看不到未绑定的 MCP 工具。若在此再塞一份工具白名单，
     * harness 的 {@code allowlistedInheritedToolkit} 会把不在名单里的
     * {@code read_file}/{@code execute} 等基础工具一并删光。
     *
     * @param binding 场景绑定；{@link ScenarioBinding#EMPTY} 表示不限制
     */
    public List<SubagentDeclaration> loadMerged(Path globalDir, Path workspaceDir,
                                                ScenarioBinding binding) {
        ScenarioBinding effective = binding == null ? ScenarioBinding.EMPTY : binding;
        Map<String, SubagentDeclaration> merged = new LinkedHashMap<>();
        for (SubagentDeclaration decl : loadFromDirectory(globalDir, effective)) {
            merged.put(decl.getName(), decl);
        }
        for (SubagentDeclaration decl : loadFromDirectory(workspaceDir, effective)) {
            if (merged.containsKey(decl.getName())) {
                log.info("子 Agent [{}] 被 workspace 级声明覆盖（workspace 优先）", decl.getName());
            }
            merged.put(decl.getName(), decl);
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 扫描目录下的子 Agent 声明文件，返回声明列表（文件不存在/为空时返回空列表）
     */
    public List<SubagentDeclaration> loadFromDirectory(Path subagentsDir) {
        return loadFromDirectory(subagentsDir, ScenarioBinding.EMPTY);
    }

    /** 扫描目录并施加场景 skill 隔离 */
    public List<SubagentDeclaration> loadFromDirectory(Path subagentsDir, ScenarioBinding binding) {
        List<SubagentDeclaration> declarations = new ArrayList<>();
        if (subagentsDir == null || !Files.isDirectory(subagentsDir)) {
            return declarations;
        }

        try (Stream<Path> stream = Files.list(subagentsDir)) {
            List<Path> files = stream
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                try {
                    SubagentDeclaration decl = parse(file, binding);
                    if (decl != null) {
                        declarations.add(decl);
                    }
                } catch (Exception e) {
                    log.warn("解析子 Agent 声明失败: {} - {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("读取子 Agent 目录失败: {} - {}", subagentsDir, e.getMessage());
        }

        if (!declarations.isEmpty()) {
            log.info("已加载 {} 个子 Agent: {}", declarations.size(),
                    declarations.stream().map(d -> d.getName()).toList());
        }
        return declarations;
    }

    private SubagentDeclaration parse(Path file, ScenarioBinding binding) throws IOException {
        String content = Files.readString(file);
        String agentId = file.getFileName().toString().replaceAll("\\.md$", "");

        // 解析 YAML frontmatter（--- 包裹）
        String description = "";
        String model = null;
        String roleName = null;
        // 未在 frontmatter 显式指定 steps 时用配置值（默认 30），而非框架的 10。
        // 框架默认 10 步对「读若干文件 + 分析 + 汇总」这类任务明显不够，
        // 耗尽后会触发 ExceedMaxItersEvent 强行结束 → 回复被截断。
        int steps = properties.getAgent().getSubagentSteps();
        boolean stepsExplicit = false;
        List<String> tools = null;
        List<String> skills = null;

        String body = content;
        if (content.trim().startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end > 0) {
                String frontmatter = content.substring(3, end);
                body = content.substring(end + 3);
                for (String line : frontmatter.split("\n")) {
                    String l = line.trim();
                    if (l.isBlank() || l.startsWith("#")) {
                        continue;
                    }
                    int idx = l.indexOf(':');
                    if (idx <= 0) {
                        continue;
                    }
                    String key = l.substring(0, idx).trim();
                    String value = l.substring(idx + 1).trim()
                            .replaceAll("^[\"']|[\"']$", "");
                    switch (key) {
                        case "description" -> description = value;
                        case "model" -> model = value;
                        case "role" -> roleName = value;
                        case "steps" -> {
                            try {
                                steps = Integer.parseInt(value);
                                stepsExplicit = true;
                            } catch (NumberFormatException ignored) {
                                // 保留默认
                            }
                        }
                        case "tools" -> tools = parseNameList(value);
                        case "skills" -> skills = parseNameList(value);
                        default -> {
                            // 忽略未知字段
                        }
                    }
                }
            }
        }

        String prompt = body.trim();
        if (prompt.isEmpty()) {
            prompt = "You are a helpful subagent named " + agentId + ".";
        }

        int configuredSteps = properties.getAgent().getSubagentSteps();
        // 显式写了但明显偏低的老声明（内置的 10/12）同样会截断，统一抬到配置下限。
        // 高于配置值的显式设置予以尊重（作者刻意放宽）。
        if (stepsExplicit && steps < configuredSteps) {
            log.info("子 Agent [{}] 声明 steps={} 低于配置下限 {}，已抬升（避免回复被截断）",
                    agentId, steps, configuredSteps);
            steps = configuredSteps;
        }

        SubagentDeclaration.Builder builder = SubagentDeclaration.builder()
                .name(agentId)
                .description(description.isBlank() ? "子 Agent: " + agentId : description)
                .inlineAgentsBody(prompt)
                .steps(steps);
        // 模型：frontmatter 显式指定优先；否则取关联角色的模型配置（团队模式按角色模型运行）
        if ((model == null || model.isBlank()) && roleName != null && !roleName.isBlank()) {
            try {
                AgentRoleEntity role = roleService.findByName(roleName.trim()).orElse(null);
                if (role != null && role.getModel() != null && !role.getModel().isBlank()) {
                    model = role.getModel();
                }
            } catch (Exception e) {
                log.debug("读取角色 {} 模型失败: {}", roleName, e.getMessage());
            }
        }
        if (model != null && !model.isBlank()) {
            builder.model(model);
        }
        if (tools != null) {
            builder.tools(tools);
        }
        List<String> effectiveSkills = restrictSkills(agentId, skills, binding);
        if (effectiveSkills != null) {
            builder.skills(effectiveSkills);
        }
        return builder.build();
    }

    /**
     * 计算子 Agent 的<b>有效 skill 白名单</b>：声明自身的 skills 与场景绑定取<b>交集</b>。
     * <p>
     * 取交集而非覆盖，是因为两个限制都有存在理由，谁都不该被绕过：
     * 声明里的 skills 是作者对该子 Agent 职责的收窄，场景绑定是运行时的能力边界。
     * <p>
     * 边界情形：
     * <ul>
     *   <li>场景无 skill 绑定 → 原样返回声明值（可能为 null = 不限制）</li>
     *   <li>声明未写 skills → 直接采用场景绑定</li>
     *   <li>交集为空 → 回退为场景绑定并记 warn。给空集会让 harness 的
     *       {@code SkillFilter.only(空)} 把该子 Agent 的 skill 全禁掉，
     *       「配置写错」不该升级成「子 Agent 不可用」</li>
     * </ul>
     *
     * @return 有效 skill 列表；{@code null} 表示不限制
     */
    private List<String> restrictSkills(String agentId, List<String> declared,
                                        ScenarioBinding binding) {
        if (binding == null || !binding.hasSkillBinding()) {
            return declared;
        }
        List<String> bound = binding.skills();
        if (declared == null || declared.isEmpty()) {
            log.info("子 Agent [{}] 未声明 skills，采用场景绑定: {}", agentId, bound);
            return bound;
        }
        List<String> intersection = new ArrayList<>();
        for (String name : declared) {
            if (containsIgnoreCase(bound, name)) {
                intersection.add(name);
            }
        }
        if (intersection.isEmpty()) {
            log.warn("子 Agent [{}] 声明的 skills {} 与场景绑定 {} 无交集，"
                    + "按场景绑定处理（避免该子 Agent 完全失去 skill）", agentId, declared, bound);
            return bound;
        }
        if (intersection.size() < declared.size()) {
            log.info("子 Agent [{}] skills 被场景收窄: {} -> {}", agentId, declared, intersection);
        }
        return intersection;
    }

    private boolean containsIgnoreCase(List<String> pool, String target) {
        for (String candidate : pool) {
            if (candidate.equalsIgnoreCase(target.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 frontmatter 中的名字列表，兼容 YAML 行内数组与裸逗号分隔两种写法：
     * <pre>
     * tools:  [read_file, grep_files]
     * skills: "clean-code", 'code-refactor'
     * </pre>
     * 与 harness {@code AgentSpecLoader.parseToolNames} 的行为保持一致。
     *
     * @return 非空名字列表；全为空白时返回 {@code null} 表示「未声明 = 不限制」
     */
    private List<String> parseNameList(String value) {
        List<String> list = new ArrayList<>();
        for (String t : value.split("[,\\[\\]\"']")) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list.isEmpty() ? null : list;
    }
}
