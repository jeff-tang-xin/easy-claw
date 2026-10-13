package com.xinl.easyclaw.api;

import com.xinl.easyclaw.capability.AgentCapabilityEntity;
import com.xinl.easyclaw.capability.AgentCapabilityService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智能体能力绑定 API：把 MCP 服务 / Skill / 工具挂到具体智能体上。
 * <p>
 * <b>为什么独立成控制器</b>：{@code ManageController} 已承载 skills/agents/tools/mcp/settings
 * 等多域端点，构造参数也成了一处「改一个域就要同步全部测试」的高耦合点；能力绑定是新的
 * 独立域，单独开一个控制器既不触碰既有构造签名，也让端点边界与前端面板一一对应。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentCapabilityController {

    private final AgentCapabilityService capabilityService;

    public AgentCapabilityController(AgentCapabilityService capabilityService) {
        this.capabilityService = capabilityService;
    }

    /** 绑定视图：kind + name + 是否启用 */
    public record CapabilityView(String kind, String name, boolean enabled) {

        static CapabilityView of(AgentCapabilityEntity entity) {
            return new CapabilityView(entity.getKind(), entity.getName(), entity.isEnabled());
        }
    }

    /** 写入条目：name 必填，enabled 缺省为 true */
    public record CapabilityBinding(String name, Boolean enabled) {
    }

    /**
     * 读取某智能体的能力绑定。
     *
     * @param kind 可选过滤：mcp / skill / tool；不传返回全部
     */
    @GetMapping("/{agentId}/capabilities")
    public List<CapabilityView> list(@PathVariable String agentId,
                                     @RequestParam(required = false) String kind) {
        List<AgentCapabilityEntity> all = capabilityService.list(agentId);
        if (kind == null || kind.isBlank()) {
            return all.stream().map(CapabilityView::of).toList();
        }
        String wanted = kind.trim().toLowerCase(java.util.Locale.ROOT);
        return all.stream()
                .filter(e -> wanted.equals(e.getKind()))
                .map(CapabilityView::of)
                .toList();
    }

    /**
     * 整组覆盖某 kind 的绑定：空数组 = 清空该 kind。
     * <p>
     * 覆盖式而非增量：前端一次提交当前勾选全集，天然幂等，也避免「取消勾选」丢失。
     */
    @PutMapping("/{agentId}/capabilities")
    public List<CapabilityView> replace(@PathVariable String agentId,
                                        @RequestParam String kind,
                                        @RequestBody List<CapabilityBinding> bindings) {
        List<AgentCapabilityService.Binding> items = bindings == null ? List.of()
                : bindings.stream().map(b -> new AgentCapabilityService.Binding(b.name(), b.enabled())).toList();
        return capabilityService.replace(agentId, kind, items).stream()
                .map(CapabilityView::of)
                .toList();
    }

    /** 删除单条绑定 */
    @DeleteMapping("/{agentId}/capabilities")
    public List<CapabilityView> remove(@PathVariable String agentId,
                                       @RequestParam String kind,
                                       @RequestParam String name) {
        capabilityService.remove(agentId, kind, name);
        return list(agentId, kind);
    }
}
