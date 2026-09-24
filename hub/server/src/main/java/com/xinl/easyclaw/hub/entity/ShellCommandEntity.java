package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * Shell 命令白名单（shell_commands，V17）：hub 统一维护（platformAdmin CRUD），spoke 只读消费。
 * cmd 全局唯一、创建后不可改；subcommands 库内存逗号分隔串（空 = 整命令放行），接口层收列表。
 */
@Getter
@Setter
@Entity
@Table(name = "shell_commands",
        uniqueConstraints = @UniqueConstraint(name = "uk_shell_cmd", columnNames = "cmd"))
public class ShellCommandEntity extends BaseEntity {

    @Column(nullable = false, length = 64)
    private String cmd;

    /** 逗号分隔的子命令白名单（空 = 整命令放行）。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String subcommands = "";

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean enabled = true;
}
