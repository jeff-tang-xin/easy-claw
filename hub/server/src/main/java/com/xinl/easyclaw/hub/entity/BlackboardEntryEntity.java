package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 黑板报条目（blackboard_entries 表，V14）：追加型、团队共享，按 project_id 归类。
 * status 为 active|archived（归档是条目的状态标签，见设计文档 §8.5，不另建归档表）。
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
}