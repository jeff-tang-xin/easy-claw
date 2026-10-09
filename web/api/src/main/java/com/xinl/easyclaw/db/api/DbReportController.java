package com.xinl.easyclaw.db.api;

import com.xinl.easyclaw.db.entity.DbReportEntity;
import com.xinl.easyclaw.db.service.DbReportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * DB 报表 REST（V32）：报表中心的列表/预览/下载/删除。
 * <p>
 * workspaceId 作必填参数（与 {@code /api/db} 其余端点一致），服务层按
 * (id, workspaceId) 双条件查询——跨工作区访问一律 404。
 * 删除用 POST（/{id}/delete）而非 DELETE 方法：与 /api/db 现有端点风格一致，
 * 前端统一走 postJson。
 */
@RestController
@RequestMapping("/api/db/reports")
public class DbReportController {

    private final DbReportService reports;

    public DbReportController(DbReportService reports) {
        this.reports = reports;
    }

    /** 报表列表（元数据投影，不含 HTML 大字段），新→旧 */
    @GetMapping
    public Map<String, Object> list(@RequestParam String workspaceId) {
        return Map.of("reports", reports.list(workspaceId));
    }

    /** 单条详情（含 htmlContent，预览 iframe 用） */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id, @RequestParam String workspaceId) {
        DbReportEntity e = reports.get(id, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "报表不存在: " + id));
        return Map.of("report", e);
    }

    /** 下载（attachment；中文标题经 ContentDisposition UTF-8 编码，非法文件名字符替换为下划线） */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @RequestParam String workspaceId) {
        DbReportEntity e = reports.get(id, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "报表不存在: " + id));
        String filename = e.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_") + ".html";
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.TEXT_HTML)
                .body(e.getHtmlContent().getBytes(StandardCharsets.UTF_8));
    }

    /** 删除报表 */
    @PostMapping("/{id}/delete")
    public Map<String, Object> delete(@PathVariable Long id, @RequestParam String workspaceId) {
        boolean deleted = reports.delete(id, workspaceId);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报表不存在: " + id);
        }
        return Map.of("deleted", true);
    }
}
