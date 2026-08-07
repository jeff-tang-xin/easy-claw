package com.xinl.easyclaw.agent;

import com.xinl.easyclaw.role.entity.AgentRoleEntity;
import com.xinl.easyclaw.role.service.RoleManagementService;
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
 * 解析 YAML frontmatter（description/model/steps/tools）与正文（系统提示词），
 * 构建 {@link SubagentDeclaration} 注册到主控 HarnessAgent。
 * <p>
 * 所有子 Agent 与主控共享同一个 Workspace，文件操作同样受沙箱限制。
 */
@Component
public class SubagentLoader {

    private static final Logger log = LoggerFactory.getLogger(SubagentLoader.class);

    private final RoleManagementService roleService;

    public SubagentLoader(RoleManagementService roleService) {
        this.roleService = roleService;
    }

    /**
     * 合并加载全局 + Workspace 两级子 Agent 声明。
     * 规则：workspace 级覆盖 global 级（同名时后者覆盖前者）。
     */
    public List<SubagentDeclaration> loadMerged(Path globalDir, Path workspaceDir) {
        Map<String, SubagentDeclaration> merged = new LinkedHashMap<>();
        for (SubagentDeclaration decl : loadFromDirectory(globalDir)) {
            merged.put(decl.getName(), decl);
        }
        for (SubagentDeclaration decl : loadFromDirectory(workspaceDir)) {
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
                    SubagentDeclaration decl = parse(file);
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

    private SubagentDeclaration parse(Path file) throws IOException {
        String content = Files.readString(file);
        String agentId = file.getFileName().toString().replaceAll("\\.md$", "");

        // 解析 YAML frontmatter（--- 包裹）
        String description = "";
        String model = null;
        String roleName = null;
        int steps = 10;
        List<String> tools = null;

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
                            } catch (NumberFormatException ignored) {
                                // 保留默认
                            }
                        }
                        case "tools" -> {
                            List<String> list = new ArrayList<>();
                            for (String t : value.split("[,\\[\\]\"']")) {
                                String trimmed = t.trim();
                                if (!trimmed.isEmpty()) {
                                    list.add(trimmed);
                                }
                            }
                            if (!list.isEmpty()) {
                                tools = list;
                            }
                        }
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
        return builder.build();
    }
}
