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
}
