package com.xinl.easyclaw.workspace.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "workspaces", indexes = {
    @Index(name = "idx_workspaces_path", columnList = "path", unique = true),
    @Index(name = "idx_workspaces_user_id", columnList = "user_id"),
    @Index(name = "idx_workspaces_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceEntity {
    @Id
    @Column(name = "id")
    private String id;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(nullable = false)
    private String name;
    
    private String description;
    
    @Column(nullable = false, unique = true)
    private String path;
    
    @Column(length = 20)
    @Builder.Default
    private String status = "active";
    
    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    
    @Column(name = "updated_at")
    private Instant updatedAt;
    
    @Column(columnDefinition = "TEXT")
    private String metadata;
}
