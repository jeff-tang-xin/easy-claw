package com.xinl.easyclaw.ops.api;

import com.xinl.easyclaw.config.CloudBootstrapService;
import com.xinl.easyclaw.config.SpokeOpsServerView;
import com.xinl.easyclaw.ops.service.OpsCommandLogReporter;
import com.xinl.easyclaw.ops.service.OpsCryptoService;
import com.xinl.easyclaw.ops.service.SshConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 运维场景 REST 接口。
 * <p>
 * 运维服务器<b>只来自 hub 下发</b>（{@code GET /api/spoke/ops-servers}，hub 已按组织 +
 * 当前 appkey 用户有效授权过滤）。spoke 不再提供本地连接配置的获取/创建/编辑/删除，
 * 也不持久化任何连接配置或凭证：
 * <ul>
 *   <li>{@code POST /api/ops/connect}：<b>只传 serverKey</b>（+ 可选的 RSA 加密密码），
 *       host/port/username/password 全部由后端按 serverKey 从下发快照解析——前端不持有
 *       服务器凭证，密码不明文过网（手输密码经 RSA-OAEP 加密，见 {@link OpsCryptoService}）；</li>
 *   <li>{@code POST /api/ops/command-log}：上报用户在 Web 终端执行的命令（异步转报 hub 审计）；</li>
 *   <li>{@code POST /api/ops/disconnect} / {@code GET /api/ops/status}：断开 / 活跃连接快照；</li>
 *   <li>{@code POST /api/ops/upload}：SFTP 上传到指定活跃连接；</li>
 *   <li>{@code GET /api/ops/download}：SFTP 流式下载远程文件。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ops")
public class OpsConnectionController {

    private static final Logger log = LoggerFactory.getLogger(OpsConnectionController.class);

    private final SshConnectionService ssh;
    private final CloudBootstrapService cloudBootstrap;
    private final OpsCryptoService crypto;
    private final OpsCommandLogReporter commandLogReporter;

    public OpsConnectionController(SshConnectionService ssh, CloudBootstrapService cloudBootstrap,
                                   OpsCryptoService crypto, OpsCommandLogReporter commandLogReporter) {
        this.ssh = ssh;
        this.cloudBootstrap = cloudBootstrap;
        this.crypto = crypto;
        this.commandLogReporter = commandLogReporter;
    }

    /**
     * 连接请求体：只带 serverKey 与可选的加密密码。
     * <p>
     * {@code encryptedPassword} = Base64(RSA-OAEP-SHA-256(utf8(密码)))，公钥取自
     * {@code GET /api/ops/public-key}；缺省时使用 hub 随目录下发的密码（仅服务端内部使用，
     * 不经过浏览器）。host/port/username 一律按 serverKey 从快照解析，前端传参不采信。
     */
    public record ConnectRequest(String workspaceId, String serverKey, String encryptedPassword) {
    }

    /** 前端加密用公钥（X.509 SPKI，Base64）；spoke 重启即换钥，前端每次连接前现取 */
    @GetMapping("/public-key")
    public Map<String, Object> publicKey() {
        return Map.of("publicKey", crypto.publicKeySpkiBase64());
    }

    /**
     * 按 serverKey 建立活跃连接（同一服务器已活跃时直接复用，不重连）。
     * 返回该工作区全部活跃连接快照。
     */
    @PostMapping("/connect")
    public Map<String, Object> connect(@RequestBody ConnectRequest req) {
        if (req == null || isBlank(req.workspaceId()) || isBlank(req.serverKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspaceId/serverKey 不能为空");
        }
        SpokeOpsServerView server = cloudBootstrap.findOpsServer(req.serverKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "serverKey 不存在或平台配置未就绪: " + req.serverKey()));
        String password = resolvePassword(req, server);
        try {
            long connId = ssh.connectByServer(req.workspaceId(), req.serverKey(), server.name(),
                    server.host(), server.port() > 0 ? server.port() : 22, server.username(), password,
                    server.osType());
            log.info("运维服务器连接: workspace={}, serverKey={}, connId={}",
                    req.workspaceId(), req.serverKey(), connId);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
        return ssh.status(req.workspaceId());
    }

    /** 密码解析优先级：前端加密密码 > hub 下发密码；两者皆无 → 400（前端弹窗让用户输入） */
    private String resolvePassword(ConnectRequest req, SpokeOpsServerView server) {
        if (!isBlank(req.encryptedPassword())) {
            try {
                String password = crypto.decrypt(req.encryptedPassword());
                if (!isBlank(password)) {
                    return password;
                }
            } catch (Exception ex) {
                // 典型场景：spoke 重启换钥后前端仍持旧公钥密文——让前端重新取公钥加密
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "密码解密失败（请重新获取公钥后重试）: " + ex.getMessage());
            }
        }
        if (server.password() != null && !server.password().isBlank()) {
            return server.password();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "该服务器未配置密码，请输入密码后重试");
    }

    // ==================== 命令审计上报（用户 Web 终端） ====================

    /** 用户命令长度上限（超长截断） */
    private static final int MAX_COMMAND_LENGTH = 2000;

    /** 用户命令上报请求体（前端行缓冲拼好整行后调用） */
    public record CommandLogRequest(String workspaceId, long connId, String command) {
    }

    /**
     * 上报一条用户在 Web 终端执行的命令（异步批量转报 hub 审计）。
     * 服务器信息从连接运行时元数据解析——前端只传 workspaceId/connId/command，不采信其他字段。
     */
    @PostMapping("/command-log")
    public Map<String, Object> commandLog(@RequestBody CommandLogRequest req) {
        if (req == null || isBlank(req.workspaceId()) || req.connId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspaceId/connId 不能为空");
        }
        if (req.command() == null || req.command().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "command 不能为空");
        }
        SshConnectionService.OpsServerInfo info = ssh.opsServerInfo(req.workspaceId(), req.connId());
        if (info == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "连接不存在或已断开: connId=" + req.connId());
        }
        String command = req.command();
        if (command.length() > MAX_COMMAND_LENGTH) {
            command = command.substring(0, MAX_COMMAND_LENGTH);
        }
        commandLogReporter.enqueue(info.serverKey(), info.name(), info.host(), command, "user");
        return Map.of("ok", true);
    }

    // ==================== 断开 / 状态 ====================

    /** 断开指定连接（connId 必填）；返回剩余活跃连接列表 */
    @PostMapping("/disconnect")
    public Map<String, Object> disconnect(@RequestParam String workspaceId, @RequestParam Long connId) {
        ssh.disconnect(workspaceId, connId);
        return ssh.status(workspaceId);
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String workspaceId) {
        return ssh.status(workspaceId);
    }

    // ==================== 文件上传（SFTP） ====================

    /** 上传文件到指定连接的远程目录（SFTP put）；targetDir 缺省为用户家目录 */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam String workspaceId,
                                      @RequestParam Long connId,
                                      @RequestParam(required = false) String targetDir,
                                      @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        }
        String err = ssh.upload(workspaceId, connId, targetDir, file.getOriginalFilename(), file.getInputStream());
        if (err != null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, err);
        }
        Map<String, Object> out = ssh.status(workspaceId);
        out.put("uploaded", file.getOriginalFilename());
        out.put("size", file.getSize());
        return out;
    }

    // ==================== 文件下载（SFTP） ====================

    /**
     * 流式下载远程文件（SFTP read）。path 为远程文件路径（绝对路径或相对家目录）。
     * 流式返回：不整读进内存，支持大文件；流关闭时连带释放 SFTP 客户端。
     */
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(@RequestParam String workspaceId,
                                                          @RequestParam Long connId,
                                                          @RequestParam String path) {
        if (isBlank(path)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path 不能为空");
        }
        SshConnectionService.RemoteFile remoteFile;
        try {
            remoteFile = ssh.openDownload(workspaceId, connId, path);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
        StreamingResponseBody body = out -> {
            try (InputStream in = remoteFile.content()) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(remoteFile.fileName()))
                .contentLength(remoteFile.size() >= 0 ? remoteFile.size() : -1)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    /** RFC 5987 filename*（UTF-8 文件名兼容中文）；filename 兜底 ASCII 化 */
    private static String contentDisposition(String fileName) {
        String ascii = fileName.replaceAll("[^\\x20-\\x7e]", "_").replace("\"", "_");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''"
                + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

