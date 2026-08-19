package com.xinl.easyclaw.workspace.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "session_messages", indexes = {
        @Index(name = "idx_sm_session_seq", columnList = "session_id, msg_seq")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    @Column(name = "workspace_id", length = 100)
    private String workspaceId;

    @Column(name = "msg_seq", nullable = false)
    private long seq;

    @Column(name = "msg_type", nullable = false, length = 20)
    private String type;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "tool_name", length = 200)
    private String toolName;

    @Column(name = "tool_args", columnDefinition = "TEXT")
    private String toolArgs;

    @Column(name = "tool_result", columnDefinition = "TEXT")
    private String toolResult;

    @Column(name = "subagent_name", length = 200)
    private String subagentName;

    @Column(name = "images", columnDefinition = "TEXT")
    private String imagesJson;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
