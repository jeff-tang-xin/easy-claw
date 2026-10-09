package com.xinl.easyclaw.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * DB 报表实体（V32）：AI 分析产出的完整 HTML 报表，供 DB 页面「📊 报表」面板展示与下载。
 * <p>
 * 产生路径：AI 完成数据分析后调用 {@code db_report} 工具保存（DbTools），落库后经
 * {@code /api/db/reports} 端点列表/预览/下载。按 workspace 隔离——所有查询都带
 * workspaceId 条件，跨工作区访问一律按不存在处理。
 * <p>
 * html_content 直接存 TEXT（SQLite 无长度语义，列宽仅作 Hibernate DDL 提示），
 * 保存侧以 {@code DbReportService.MAX_HTML_CHARS} 硬校验，防失控输出撑爆行。
 */
@Entity
@Table(name = "db_reports", indexes = @Index(name = "idx_db_reports_ws", columnList = "workspace_id, created_at"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, length = 64)
    private String workspaceId;

    /** 来源连接（保存时刻的会话绑定连接；连接断开后仅作展示溯源，不参与查询） */
    @Column(name = "conn_key", length = 64)
    private String connKey;

    @Column(name = "server_key", length = 128)
    private String serverKey;

    @Column(name = "server_name", length = 128)
    private String serverName;

    @Column(name = "db_type", length = 32)
    private String dbType;

    /** 列名避开 database（部分库型的保留字风险） */
    @Column(name = "database_name", length = 128)
    private String databaseName;

    @Column(name = "title", nullable = false, length = 256)
    private String title;

    /** report 行必填；dashboard 行为 null（数据在 blocks） */
    @Column(name = "html_content", length = 5_242_880)
    private String htmlContent;

    /** 记录类型：report（HTML 快照）/ dashboard（区块化看板）。存量行 null 视为 report。 */
    @Column(name = "kind", length = 16)
    private String kind;

    /** 看板区块清单 JSON（kind=dashboard 时非空）：[{type,label,sql?,text?,connKey?}] */
    @Column(name = "blocks", length = 1_048_576)
    private String blocks;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
