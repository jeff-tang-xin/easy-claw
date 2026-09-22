package com.xinl.easyclaw.ops.api;

import com.xinl.easyclaw.ops.entity.OpsConnectionEntity;
import com.xinl.easyclaw.ops.repository.OpsConnectionRepository;
import com.xinl.easyclaw.ops.service.LocalCryptoService;
import com.xinl.easyclaw.ops.service.SshConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维场景 REST 接口：连接配置 CRUD（凭证加密落库）+ 连接/断开 + 状态 + 文件上传。
 * <p>
 * <b>凭证红线</b>：password/privateKey/passphrase 只在请求体里进、加密后落库，
 * 任何响应都不回显明文或密文（只回 hasPassword / hasKey 布尔位）。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsConnectionController {

    private static final Logger log = LoggerFactory.getLogger(OpsConnectionController.class);

    private final OpsConnectionRepository repo;
    private final LocalCryptoService crypto;
    private final SshConnectionService ssh;

    public OpsConnectionController(OpsConnectionRepository repo,
                                   LocalCryptoService crypto,
                                   SshConnectionService ssh) {
        this.repo = repo;
        this.crypto = crypto;
        this.ssh = ssh;
    }

    // ==================== 连接配置 CRUD ====================

    /** 工作区的连接列表（脱敏：不含任何凭证字段） */
    @GetMapping("/connections")
    public List<Map<String, Object>> list(@RequestParam String workspaceId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OpsConnectionEntity e : repo.findByWorkspaceIdOrderByIdAsc(workspaceId)) {
            out.add(toSafeView(e));
        }
        return out;
    }

    /** 新建连接配置（凭证加密落库） */
    @PostMapping("/connections")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String workspaceId = str(body, "workspaceId");
        String name = str(body, "name");
        String host = str(body, "host");
        String username = str(body, "username");
        String authType = str(body, "authType");
        if (workspaceId == null || name == null || host == null || username == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspaceId/name/host/username 不能为空");
        }
        if (repo.existsByWorkspaceIdAndName(workspaceId, name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接名已存在: " + name);
        }
        OpsConnectionEntity e = OpsConnectionEntity.builder()
                .workspaceId(workspaceId)
                .name(name)
                .host(host)
                .port(intVal(body, "port", 22))
                .username(username)
                .authType("key".equals(authType) ? "key" : "password")
                .build();
        applySecrets(e, body, null);
        e = repo.save(e);
        log.info("新建运维连接: workspace={}, name={}, {}@{}", workspaceId, name, username, host);
        return toSafeView(e);
    }

    /** 更新连接配置；凭证字段传空/缺省 = 保持原值不变 */
    @PutMapping("/connections/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String workspaceId = str(body, "workspaceId");
        OpsConnectionEntity e = repo.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "连接不存在"));
        String name = str(body, "name");
        if (name != null && !name.equals(e.getName()) && repo.existsByWorkspaceIdAndName(workspaceId, name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接名已存在: " + name);
        }
        if (name != null) {
            e.setName(name);
        }
        if (str(body, "host") != null) {
            e.setHost(str(body, "host"));
        }
        Integer port = intVal(body, "port", e.getPort());
        if (port != null && port > 0) {
            e.setPort(port);
        }
        if (str(body, "username") != null) {
            e.setUsername(str(body, "username"));
        }
        if (str(body, "authType") != null) {
            e.setAuthType("key".equals(str(body, "authType")) ? "key" : "password");
        }
        applySecrets(e, body, e);
        e = repo.save(e);
        log.info("更新运维连接: id={}, workspace={}", id, workspaceId);
        return toSafeView(e);
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @RequestParam String workspaceId) {
        OpsConnectionEntity e = repo.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "连接不存在"));
        repo.delete(e);
        log.info("删除运维连接: id={}, workspace={}, name={}", id, workspaceId, e.getName());
        return ResponseEntity.noContent().build();
    }

    // ==================== 连接 / 断开 / 状态 ====================

    /** 用指定连接配置建立活跃连接（解密凭证 → SSH 连接+认证）；同工作区其他连接不受影响 */
    @PostMapping("/connections/{id}/connect")
    public Map<String, Object> connect(@PathVariable Long id, @RequestParam String workspaceId) {
        OpsConnectionEntity e = repo.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "连接不存在"));
        try {
            ssh.connect(workspaceId, e.getId(), e.getName(), e.getHost(), e.getPort(), e.getUsername(),
                    e.getAuthType(),
                    "password".equals(e.getAuthType()) ? crypto.decrypt(e.getPasswordEnc()) : null,
                    "key".equals(e.getAuthType()) ? crypto.decrypt(e.getPrivateKeyEnc()) : null,
                    crypto.decrypt(e.getKeyPassphraseEnc()));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
        return ssh.status(workspaceId);
    }

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
        Map<String, Object> out = new HashMap<>(ssh.status(workspaceId));
        out.put("uploaded", file.getOriginalFilename());
        out.put("size", file.getSize());
        return out;
    }

    // ==================== 内部 ====================

    /** 把请求体里的凭证字段加密写入实体；update 模式下空值 = 保持原值 */
    private void applySecrets(OpsConnectionEntity target, Map<String, Object> body, OpsConnectionEntity existing) {
        String password = str(body, "password");
        String privateKey = str(body, "privateKey");
        String passphrase = str(body, "keyPassphrase");
        if ("key".equals(target.getAuthType())) {
            if (privateKey != null && !privateKey.isBlank()) {
                target.setPrivateKeyEnc(crypto.encrypt(privateKey));
            } else if (existing == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "私钥认证必须提供 privateKey");
            }
            target.setKeyPassphraseEnc(passphrase != null && !passphrase.isBlank()
                    ? crypto.encrypt(passphrase) : null);
            target.setPasswordEnc(existing != null ? existing.getPasswordEnc() : null);
        } else {
            if (password != null && !password.isBlank()) {
                target.setPasswordEnc(crypto.encrypt(password));
            } else if (existing == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码认证必须提供 password");
            }
            target.setPrivateKeyEnc(existing != null ? existing.getPrivateKeyEnc() : null);
            target.setKeyPassphraseEnc(existing != null ? existing.getKeyPassphraseEnc() : null);
        }
    }

    /** 脱敏视图：绝不包含凭证明文/密文 */
    private static Map<String, Object> toSafeView(OpsConnectionEntity e) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", e.getId());
        out.put("workspaceId", e.getWorkspaceId());
        out.put("name", e.getName());
        out.put("host", e.getHost());
        out.put("port", e.getPort());
        out.put("username", e.getUsername());
        out.put("authType", e.getAuthType());
        out.put("hasPassword", e.getPasswordEnc() != null && !e.getPasswordEnc().isEmpty());
        out.put("hasKey", e.getPrivateKeyEnc() != null && !e.getPrivateKeyEnc().isEmpty());
        return out;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intVal(Map<String, Object> body, String key, Integer def) {
        Object v = body.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> cryptoError(IllegalStateException e) {
        // LocalCryptoService 的加解密失败（密钥文件缺失/不匹配）→ 502，消息可直接展示
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(e.getMessage());
    }
}
