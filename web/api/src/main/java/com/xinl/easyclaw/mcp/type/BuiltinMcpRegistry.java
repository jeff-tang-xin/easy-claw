package com.xinl.easyclaw.mcp.type;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内置 MCP 类型注册表 —— 把「内置」的判定从服务名字符串收敛到类型标识。
 * <p>
 * 收集 Spring 容器里全部 {@link BuiltinMcpType} 实现，按 {@code typeId} 建索引。
 * 新增一种内置能力 = 加一个 {@code @Component} 实现类，MCP 服务层与前端零改动。
 * <p>
 * <b>重复 typeId 的处理</b>：保留先注册的并告警，与 {@code AgentRegistry} 同一取向——
 * 静默覆盖会让「我明明改了代码怎么不生效」变成无从排查的问题。
 */
@Component
public class BuiltinMcpRegistry {

    private static final Logger log = LoggerFactory.getLogger(BuiltinMcpRegistry.class);

    private final Map<String, BuiltinMcpType> byId;

    public BuiltinMcpRegistry(List<BuiltinMcpType> types) {
        Map<String, BuiltinMcpType> map = new LinkedHashMap<>();
        for (BuiltinMcpType type : types) {
            String id = normalize(type.typeId());
            if (id.isEmpty()) {
                log.error("[BuiltinMcpType] {} 的 typeId() 返回空，已跳过", type.getClass().getName());
                continue;
            }
            BuiltinMcpType previous = map.putIfAbsent(id, type);
            if (previous != null) {
                log.warn("[BuiltinMcpType] 类型 [{}] 重复注册，保留 {}，跳过 {}",
                        id, previous.getClass().getName(), type.getClass().getName());
            }
        }
        this.byId = Map.copyOf(map);
        log.info("已注册内置 MCP 能力类型: {}", byId.keySet());
    }

    /** 按类型标识查找（大小写不敏感、容忍空白）；未注册返回 empty。 */
    public Optional<BuiltinMcpType> find(String typeId) {
        return Optional.ofNullable(byId.get(normalize(typeId)));
    }

    /** 全部已注册类型（按 typeId 字典序，保证前端下拉顺序稳定）。 */
    public List<BuiltinMcpType> all() {
        return byId.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    /** 是否已注册该类型（create 校验用）。 */
    public boolean supports(String typeId) {
        return find(typeId).isPresent();
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
    }
}
