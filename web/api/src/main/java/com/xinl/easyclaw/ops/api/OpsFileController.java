package com.xinl.easyclaw.ops.api;

import com.xinl.easyclaw.ops.service.SshConnectionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 运维文件管理 REST 接口（SFTP，复用活跃 SSH 会话）。
 * <p>
 * 与 {@link OpsConnectionController} 的 upload/download 端点配套，构成文件管理面板的
 * 全部后端能力：
 * <ul>
 *   <li>{@code GET  /api/ops/files}：列目录（懒加载树，点开一层调一次）；</li>
 *   <li>{@code POST /api/ops/files/mkdir}：新建目录（父目录须存在）；</li>
 *   <li>{@code POST /api/ops/files/create}：新建空文件（目标已存在则拒绝，防误截断）；</li>
 *   <li>{@code POST /api/ops/files/rename}：重命名/移动（源不存在或目标已存在则拒绝）。</li>
 * </ul>
 * 写操作只做「存在性」防呆，不做路径沙箱限制——运维对象就是整台服务器，管理员本就
 * 需要访问任意路径；连接级授权已在 connect 时校验。P1 不提供删除端点。
 */
@RestController
@RequestMapping("/api/ops/files")
public class OpsFileController {

    private final SshConnectionService ssh;

    public OpsFileController(SshConnectionService ssh) {
        this.ssh = ssh;
    }

    /** 列目录；path 缺省为远程用户家目录。 */
    @GetMapping
    public List<SshConnectionService.RemoteEntry> list(@RequestParam String workspaceId,
                                                       @RequestParam Long connId,
                                                       @RequestParam(required = false) String path) {
        try {
            return ssh.listDir(workspaceId, connId, path);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    /** 新建目录 */
    @PostMapping("/mkdir")
    public SshConnectionService.RemoteEntry mkdir(@RequestBody PathRequest req) {
        try {
            ssh.makeDirectory(req.workspaceId(), req.connId(), req.path());
            return new SshConnectionService.RemoteEntry(
                    baseName(req.path()), req.path(), true, 0L, System.currentTimeMillis());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    /** 新建空文件 */
    @PostMapping("/create")
    public SshConnectionService.RemoteEntry create(@RequestBody PathRequest req) {
        try {
            ssh.createFile(req.workspaceId(), req.connId(), req.path());
            return new SshConnectionService.RemoteEntry(
                    baseName(req.path()), req.path(), false, 0L, System.currentTimeMillis());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    /** 重命名/移动 */
    @PostMapping("/rename")
    public Map<String, String> rename(@RequestBody RenameRequest req) {
        try {
            ssh.rename(req.workspaceId(), req.connId(), req.from(), req.to());
            return Map.of("from", req.from(), "to", req.to());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    public record PathRequest(String workspaceId, Long connId, String path) {
    }

    public record RenameRequest(String workspaceId, Long connId, String from, String to) {
    }

    private static String baseName(String path) {
        String p = path.trim();
        int idx = p.lastIndexOf('/');
        return idx >= 0 && idx < p.length() - 1 ? p.substring(idx + 1) : p;
    }
}
