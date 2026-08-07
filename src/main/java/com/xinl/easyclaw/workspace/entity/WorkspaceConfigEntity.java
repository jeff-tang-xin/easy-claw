package com.xinl.easyclaw.workspace.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "workspace_configs", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"workspace_id", "config_type", "config_key"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceConfigEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;
    
    @Column(name = "config_type", nullable = false, length = 20)
    private String configType;
    
    @Column(name = "config_key", nullable = false)
    private String configKey;
    
    @Column(columnDefinition = "TEXT")
    private String configValue;
    
    @Column(name = "created_at")
    private Instant createdAt;
    
    @Column(name = "updated_at")
    private Instant updatedAt;
}
