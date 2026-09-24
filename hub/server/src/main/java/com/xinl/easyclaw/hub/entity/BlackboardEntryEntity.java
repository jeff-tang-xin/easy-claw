package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 黑板报条目（blackboard_entries 表，V14；V24 起含 spoke 同步条目）：追加型、团队共享，按 project_id 归类。
 * status 为 active|archived（归档是条目的状态标签，见设计文档 §8.5，不另建归档表）。
 * source = platform（hub 平台用户）| workspace（spoke Agent 同步写入，author_user_id=0 占位）。
 * book_key = 分本语义（平台条目固定 'main'）；entry_type 仅 spoke 条目有（finding/risk/note…）。
 * 无外键（V4 决策），project/user 一致性由应用层保证。
 */
@Getter
@Setter
@Entity
@Table(name = "blackboard_entries")
public class BlackboardEntryEntity extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "author_user_id", nullable = false)
    private Long authorUserId;

    /** active | archived */
    @Column(nullable = false, length = 20)
    private String status = "active";

    /** platform = hub 平台写入 | workspace = spoke Agent 同步写入（V24） */
    @Column(nullable = false, length = 20)
    private String source = "platform";

    /** spoke 来源工作区标识（source=workspace 时非空） */
    @Column(name = "source_workspace_id", length = 64)
    private String sourceWorkspaceId;

    /** 条目类型（spoke 写入：finding/risk/note…；平台写入为 null） */
    @Column(name = "entry_type", length = 32)
    private String entryType;

    /** 分本键：平台条目固定 'main'；spoke 条目为其写入时的本名（归档迁移后已去 .archived- 后缀） */
    @Column(name = "book_key", length = 192)
    private String bookKey;
}