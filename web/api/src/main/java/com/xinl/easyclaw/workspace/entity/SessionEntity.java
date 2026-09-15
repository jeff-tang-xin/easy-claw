package com.xinl.easyclaw.workspace.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "sessions", indexes = {
    @Index(name = "idx_sessions_workspace_id", columnList = "workspace_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SessionEntity {
    @Id
    @EqualsAndHashCode.Include
    private String id;
    
    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;
    
    private String title;
    
    @Column(length = 20)
    @Builder.Default
    private String status = "active";
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    
    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;

    /**
     * 会话挂载的 git worktree 绝对路径；null = 未挂载（默认，行为与旧版一致）。
     * 位于 {@code <workspace>/.easyclaw-worktrees/<sessionId>}（2026-09-14 会话↔worktree 挂钩）。
     */
    @Column(name = "worktree_path", length = 500)
    private String worktreePath;

    /**
     * worktree 检出的分支名；null = 未挂载。与 worktreePath 同生同灭。
     */
    @Column(name = "branch", length = 200)
    private String branch;
}
