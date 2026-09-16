package com.xinl.easyclaw.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import com.xinl.easyclaw.hub.common.BaseEntity;

/**
 * 协作文档当前态（docs 表，V7）：归属项目（project_id）；需求/任务由 doc_type 区分，
 * task 经 parent_doc_id 挂到同项目需求下。version 为乐观锁版本号，每次内容/标题变更 +1。
 * 无外键（V4 决策），project/user 一致性由应用层保证。
 */
@Getter
@Setter
@Entity
@Table(name = "docs")
public class DocEntity extends BaseEntity {

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "parent_doc_id")
    private Long parentDocId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content = "";

    /** requirement | task */
    @Column(name = "doc_type", nullable = false, length = 20)
    private String docType = "requirement";

    /** active | archived */
    @Column(nullable = false, length = 20)
    private String status = "active";

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "assignee_user_id")
    private Long assigneeUserId;

    @Column(nullable = false)
    private Long version = 1L;
}
