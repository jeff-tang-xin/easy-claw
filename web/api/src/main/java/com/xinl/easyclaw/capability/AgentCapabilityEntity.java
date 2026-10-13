package com.xinl.easyclaw.capability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 智能体级能力绑定：某个智能体（主控或子 Agent）被显式授予/收口的能力项。
 * <p>
 * <b>与场景绑定的分工</b>：场景绑定回答「这个场景允许什么」，本表回答「这个智能体用什么」。
 * 内置能力实例（如锁定了某个库的 db MCP）正是靠它落到具体智能体上——否则「给 db 子 Agent
 * 开一个库」只能靠把服务塞进全局工具集，那等于对所有工作区放权。
 * <p>
 * <b>只能收窄，不能放宽</b>：生效顺序为「工作区硬约束 &gt; 场景绑定 &gt; agent 绑定 &gt; 默认」，
 * 专属 db/ops 工作区与场景显式配置过的能力，agent 绑定都不得突破（见
 * {@code WorkspaceAgentBuilder} 与 {@code SubagentLoader#restrictTools}）。
 *
 * @param kind 能力类型：{@code mcp}（服务名）/ {@code skill} / {@code tool}
 * @param name 能力标识：kind=mcp 时为 MCP 服务名
 * @param enabled false 表示显式关闭（比「不绑定」更强：它会参与交集收窄）
 */
@Entity
@Table(name = "agent_capabilities", indexes = {
        @Index(name = "idx_agent_capabilities_agent", columnList = "agent_id, kind, name", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCapabilityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 智能体标识：主控为 {@code main}，子 Agent 为其声明名（如 {@code db}、{@code reviewer}） */
    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false, length = 128)
    private String name;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
