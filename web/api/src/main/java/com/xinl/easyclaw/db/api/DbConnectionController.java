package com.xinl.easyclaw.db.api;

import com.xinl.easyclaw.config.CloudBootstrapService;
import com.xinl.easyclaw.config.SpokeDbConnectionView;
import com.xinl.easyclaw.db.service.DbConnectionService;
import com.xinl.easyclaw.ops.service.OpsCryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 数据库场景 REST 接口（V30）。
 * <p>
 * 数据库连接<b>只来自 hub 下发</b>（{@code GET /api/spoke/resources/db-connection}，hub 已按
 * 组织 + 当前 appkey 用户有效授权过滤）。spoke 不持久化任何连接配置或凭证：
 * <ul>
 *   <li>{@code POST /api/db/connect}：只传 serverKey + database（+ 可选 RSA 加密密码），
 *       host/port/username/password 全部由后端按 serverKey 从下发快照解析——前端不持有
 *       数据库凭证，密码不明文过网（手输密码经 RSA-OAEP 加密，复用 {@link OpsCryptoService}）；</li>
 *   <li>{@code GET /api/db/databases}：该连接实例上可用的库/schema 清单（「库」二级选择器）；</li>
 *   <li>{@code POST /api/db/disconnect} / {@code GET /api/db/status}：断开 / 活跃连接快照。</li>
 * </ul>
 * 每个连接实例的「库」是连接属性：每个 (serverKey, database) 组合 = 独立物理连接，
 * 换库 = 建新连接（规避连接级状态被多会话污染，设计定稿）。
 */
@RestController
@RequestMapping("/api/db")
public class DbConnectionController {

    private static final Logger log = LoggerFactory.getLogger(DbConnectionController.class);

    private final DbConnectionService db;
    private final CloudBootstrapService cloudBootstrap;
    private final OpsCryptoService crypto;

    public DbConnectionController(DbConnectionService db, CloudBootstrapService cloudBootstrap,
                                  OpsCryptoService crypto) {
        this.db = db;
        this.cloudBootstrap = cloudBootstrap;
        this.crypto = crypto;
    }

    /**
     * 连接请求体：只带 serverKey + database 与可选的加密密码。
     * <p>
     * {@code encryptedPassword} = Base64(RSA-OAEP-SHA-256(utf8(密码)))，公钥取自
     * {@code GET /api/ops/public-key}（与运维共用同一密钥对）；缺省时使用 hub 随目录
     * 下发的密码（仅服务端内部使用，不经过浏览器）。
     */
    public record ConnectRequest(String workspaceId, String serverKey, String database,
                                 String encryptedPassword) {
    }

    /**
     * 按 (serverKey, database) 建立或复用物理连接。
     * 返回该工作区全部活跃连接快照（含 connKey，工具层凭它引用会话）。
     */
    @PostMapping("/connect")
    public Map<String, Object> connect(@RequestBody ConnectRequest req) {
        if (req == null || isBlank(req.workspaceId()) || isBlank(req.serverKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspaceId/serverKey 不能为空");
        }
        SpokeDbConnectionView cfg = cloudBootstrap.findDbConnection(req.serverKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "serverKey 不存在或平台配置未就绪: " + req.serverKey()));
        String database = isBlank(req.database()) ? cfg.databaseName() : req.database().trim();
        String password = resolvePassword(req, cfg);
        String connKey;
        try {
            connKey = db.connect(req.workspaceId(), req.serverKey(), database, password);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
        log.info("数据库连接: workspace={}, serverKey={}, database={}",
                req.workspaceId(), req.serverKey(), database);
        Map<String, Object> out = Map.of("connKey", connKey, "connections", db.status(req.workspaceId()));
        return out;
    }

    /** 密码解析优先级：前端加密密码 > hub 下发密码；两者皆无 → 400（前端弹窗让用户输入） */
    private String resolvePassword(ConnectRequest req, SpokeDbConnectionView cfg) {
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
        if (cfg.password() != null && !cfg.password().isBlank()) {
            return cfg.password();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "该数据库连接未配置密码，请输入密码后重试");
    }

    /**
     * 该连接实例上可用的库/schema 清单（「库」二级选择器数据源）。
     * 用目录默认库建临时连接查询，查完即关。password 解析同 connect。
     */
    @GetMapping("/databases")
    public Map<String, Object> databases(@RequestParam String serverKey,
                                         @RequestParam(required = false) String encryptedPassword) {
        if (isBlank(serverKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "serverKey 不能为空");
        }
        SpokeDbConnectionView cfg = cloudBootstrap.findDbConnection(serverKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "serverKey 不存在或平台配置未就绪: " + serverKey));
        String password = resolvePassword(
                new ConnectRequest(null, serverKey, null, encryptedPassword), cfg);
        try {
            List<String> list = db.databases(serverKey, password);
            return Map.of("databases", list, "defaultDatabase", cfg.databaseName());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
    }

    /** 断开指定连接（connKey 必填）；返回剩余活跃连接列表 */
    @PostMapping("/disconnect")
    public Map<String, Object> disconnect(@RequestParam String workspaceId,
                                          @RequestParam String connKey) {
        return Map.of("connections", db.disconnect(workspaceId, connKey));
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String workspaceId) {
        return Map.of("connections", db.status(workspaceId));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
