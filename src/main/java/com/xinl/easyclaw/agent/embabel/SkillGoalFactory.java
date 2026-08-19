package com.xinl.easyclaw.agent.embabel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SkillGoalFactory {

    private static final Logger log = LoggerFactory.getLogger(SkillGoalFactory.class);

    private final SkillLoader skillLoader;

    public SkillGoalFactory(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
    }

    /**
     * 从 SKILL.md 的 frontmatter 解析 SkillMeta（name/pre/tags/examples/description）。
     * 如果没有 frontmatter，用文件名当 name，正文当 description。
     */
    public SkillMeta parseSkillMeta(String skillName, String workspaceId) {
        String raw = skillLoader.loadSkillRaw(workspaceId, skillName);
        if (raw == null) {
            return new SkillMeta(skillName, skillName,
                    Set.of(), Set.of(), List.of(), "");
        }

        String trimmed = raw.replaceFirst("^\\uFEFF?", "");
        String frontmatter = null;
        String body = trimmed;

        if (trimmed.startsWith("---")) {
            int end = trimmed.indexOf("---", 3);
            if (end > 0) {
                frontmatter = trimmed.substring(3, end).trim();
                body = trimmed.substring(end + 3).trim();
            }
        }

        String name = skillName;
        String description = body;
        Set<String> pre = Set.of();
        Set<String> tags = Set.of();
        List<String> examples = List.of();

        if (frontmatter != null && !frontmatter.isBlank()) {
            YamlMap yaml = parseSimpleYaml(frontmatter);
            name = yaml.getStr("name", skillName);
            String descYaml = yaml.getStr("description", null);
            if (descYaml != null && !descYaml.isBlank()) {
                description = descYaml + "\n\n" + body;
            }
            pre = yaml.getStrSet("pre");
            tags = yaml.getStrSet("tags");
            examples = yaml.getStrList("examples");
        }

        return new SkillMeta(name, skillName, pre, tags, examples, description);
    }

    /**
     * 解析简单 YAML frontmatter（只支持我们 SKILL.md 用到的字段）。
     */
    private YamlMap parseSimpleYaml(String text) {
        YamlMap result = new YamlMap();
        String[] lines = text.split("\n");
        String currentKey = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            if (trimmed.startsWith("- ") && currentKey != null) {
                result.addListValue(currentKey, trimmed.substring(2).trim());
                continue;
            }

            int colon = trimmed.indexOf(':');
            if (colon > 0) {
                String key = trimmed.substring(0, colon).trim();
                String val = trimmed.substring(colon + 1).trim();
                currentKey = key;
                if (!val.isEmpty()) {
                    result.setScalar(key, val);
                }
            }
        }
        return result;
    }

    private static class YamlMap {
        final Map<String, String> scalars = new LinkedHashMap<>();
        final Map<String, List<String>> lists = new LinkedHashMap<>();

        void setScalar(String key, String value) { scalars.put(key, stripQuotes(value)); }

        void addListValue(String key, String value) {
            lists.computeIfAbsent(key, k -> new ArrayList<>()).add(stripQuotes(value));
        }

        String getStr(String key, String def) { return scalars.getOrDefault(key, def); }

        Set<String> getStrSet(String key) {
            List<String> list = lists.get(key);
            if (list != null && !list.isEmpty()) return Set.copyOf(list);
            String scalar = scalars.get(key);
            if (scalar != null && !scalar.isBlank()) {
                return Set.copyOf(Arrays.asList(scalar.split("[,，]")));
            }
            return Set.of();
        }

        List<String> getStrList(String key) {
            List<String> list = lists.get(key);
            if (list != null) return List.copyOf(list);
            return List.of();
        }

        private String stripQuotes(String s) {
            if (s == null) return null;
            s = s.trim();
            if ((s.startsWith("\"") && s.endsWith("\"")) ||
                (s.startsWith("'") && s.endsWith("'"))) {
                return s.substring(1, s.length() - 1);
            }
            return s;
        }
    }

    /** 全部可用 skill 的 SkillMeta */
    public List<SkillMeta> parseAllSkillMetas(String workspaceId, List<String> skillNames) {
        List<SkillMeta> result = new ArrayList<>();
        for (String name : skillNames) {
            try {
                SkillMeta meta = parseSkillMeta(name, workspaceId);
                result.add(meta);
            } catch (Exception e) {
                log.warn("解析 skill 失败: name={}, err={}", name, e.getMessage());
            }
        }
        return result;
    }

    public record SkillMeta(
            String name,
            String originalName,
            Set<String> preconditions,
            Set<String> tags,
            List<String> examples,
            String description
    ) {}
}
