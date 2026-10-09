package com.xinl.easyclaw.db.service;

import com.xinl.easyclaw.db.entity.DbReportEntity;
import com.xinl.easyclaw.db.repository.DbReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * DB 报表服务（V32）：AI 分析产出的 HTML 报表落库与查询。
 * <p>
 * 保存入口是 {@code db_report} 工具（DbTools）——AI 完成分析后主动调用；
 * 查询出口是 {@code /api/db/reports} 端点（DbReportController）。
 * 校验：标题/内容非空、HTML 超长拒绝（防失控输出撑爆 SQLite 行）。
 */
@Service
public class DbReportService {

    private static final Logger log = LoggerFactory.getLogger(DbReportService.class);

    /** 单份报表 HTML 上限（5MB 字符） */
    public static final int MAX_HTML_CHARS = 5_000_000;

    private final DbReportRepository repo;
    private final DbDashboardRenderer dashboardRenderer;

    public DbReportService(DbReportRepository repo, DbDashboardRenderer dashboardRenderer) {
        this.repo = repo;
        this.dashboardRenderer = dashboardRenderer;
    }

    @Transactional
    public DbReportEntity save(String workspaceId, String connKey, String serverKey, String serverName,
                               String dbType, String databaseName, String title, String html) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId 不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("报表标题不能为空");
        }
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("报表内容不能为空");
        }
        String trimmedTitle = title.trim();
        if (trimmedTitle.length() > 256) {
            trimmedTitle = trimmedTitle.substring(0, 256);
        }
        if (html.length() > MAX_HTML_CHARS) {
            throw new IllegalArgumentException(
                    "报表过大（" + html.length() + " 字符，上限 " + MAX_HTML_CHARS + "）——请精简图表与数据后重试");
        }
        DbReportEntity saved = repo.save(DbReportEntity.builder()
                .workspaceId(workspaceId)
                .connKey(connKey)
                .serverKey(serverKey)
                .serverName(serverName)
                .dbType(dbType)
                .databaseName(databaseName)
                .title(trimmedTitle)
                .kind("report")
                .htmlContent(html)
                .build());
        log.info("DB 报表已保存: id={}, ws={}, title={}, size={}",
                saved.getId(), workspaceId, trimmedTitle, html.length());
        return saved;
    }

    /**
     * 保存数据看板（kind=dashboard）：存区块清单 JSON，不存渲染结果——
     * 打开/刷新时由 {@link DbDashboardRenderer} 实时执行渲染。
     * 保存即校验 blocks 结构（类型合法/SQL 非空），AI 传错立即反馈。
     */
    @Transactional
    public DbReportEntity saveDashboard(String workspaceId, String connKey, String serverKey, String serverName,
                                        String dbType, String databaseName, String title, String blocksJson) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId 不能为空");
        }
        List<DbDashboardRenderer.Block> blocks = dashboardRenderer.parseBlocks(blocksJson);
        String trimmedTitle = title == null || title.isBlank() ? "未命名看板" : title.trim();
        if (trimmedTitle.length() > 256) {
            trimmedTitle = trimmedTitle.substring(0, 256);
        }
        DbReportEntity saved = repo.save(DbReportEntity.builder()
                .workspaceId(workspaceId)
                .connKey(connKey)
                .serverKey(serverKey)
                .serverName(serverName)
                .dbType(dbType)
                .databaseName(databaseName)
                .title(trimmedTitle)
                .kind("dashboard")
                .blocks(blocksJson)
                .build());
        log.info("DB 看板已保存: id={}, ws={}, title={}, blocks={}",
                saved.getId(), workspaceId, trimmedTitle, blocks.size());
        return saved;
    }

    /**
     * 刷新看板：实时执行全部区块 SQL 并渲染 HTML（「每次打开就查询一次」的落点）。
     * 仅 kind=dashboard 可刷新；report 是静态快照，返回 null 由控制器映射 400。
     */
    @Transactional(readOnly = true)
    public String refreshDashboard(Long id, String workspaceId) {
        DbReportEntity e = repo.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("看板不存在: " + id));
        if (!"dashboard".equals(e.getKind())) {
            return null;
        }
        return dashboardRenderer.render(workspaceId, e.getConnKey(), e.getBlocks());
    }

    /** 列表（元数据投影，不含 htmlContent），新→旧排序 */
    @Transactional(readOnly = true)
    public List<DbReportMeta> list(String workspaceId) {
        return repo.listMeta(workspaceId);
    }

    /** 详情（含 htmlContent，预览用）；跨工作区访问返回 empty（控制器映射 404） */
    @Transactional(readOnly = true)
    public Optional<DbReportEntity> get(Long id, String workspaceId) {
        return repo.findByIdAndWorkspaceId(id, workspaceId);
    }

    /** 删除；跨工作区访问返回 false（控制器映射 404） */
    @Transactional
    public boolean delete(Long id, String workspaceId) {
        Optional<DbReportEntity> e = repo.findByIdAndWorkspaceId(id, workspaceId);
        if (e.isEmpty()) {
            return false;
        }
        repo.delete(e.get());
        log.info("DB 报表已删除: id={}, ws={}", id, workspaceId);
        return true;
    }
}
