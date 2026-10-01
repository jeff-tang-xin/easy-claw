package com.xinl.easyclaw.db.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.config.CloudProperties;
import com.xinl.easyclaw.config.HubSpokeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库连接授权守卫（V30，照 {@link com.xinl.easyclaw.ops.service.OpsConnectionGuard} 同构）。
 * <p>
 * 目录同步（cloud-config 轮询）只影响「列表可见性」——已建立的 JDBC 连接不随列表消失而断开，
 * 撤销授权后用户仍可继续查询，属于安全洞。本守卫每 10s 对活跃数据库连接批量调
 * {@code POST /api/spoke/resources/db-connection/authorize-check}（appkey 鉴权），hub 按
 * 「组织归属 + 用户未过期授权」逐个裁决；未授权（含连接被删/禁用）的连接立即断开并 warn——
 * 前端经 status 轮询感知 tab 消失。
 * <p>
 * local 模式（hubUrl/appKey 缺失）或无活跃连接时不触网。校验失败（hub 不可达）保持
 * 现状不断开——网络抖动不应误伤正常会话，授权撤销在 hub 恢复后下一轮生效。
 */
@Component
public class DbConnectionGuard {

    private static final Logger log = LoggerFactory.getLogger(DbConnectionGuard.class);
    private static final String CHECK_PATH = "/api/spoke/resources/db-connection/authorize-check";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DbConnectionService db;
    private final HubSpokeClient hub;
    private final CloudProperties cloudProperties;

    public DbConnectionGuard(DbConnectionService db, HubSpokeClient hub, CloudProperties cloudProperties) {
        this.db = db;
        this.hub = hub;
        this.cloudProperties = cloudProperties;
    }

    /** 每 10s 校验一轮活跃连接授权；无授权连接立即断开。 */
    @Scheduled(fixedDelay = 10_000L)
    public void check() {
        if (!cloudConfigured()) {
            return;
        }
        List<DbConnectionService.ActiveDbConn> conns = db.activeDbConns();
        if (conns.isEmpty()) {
            return;
        }
        List<String> serverKeys = conns.stream().map(DbConnectionService.ActiveDbConn::serverKey)
                .distinct().toList();
        Map<String, Boolean> verdict;
        try {
            verdict = parse(hub.post(CHECK_PATH, Map.of("serverKeys", serverKeys)));
        } catch (Exception e) {
            log.debug("数据库授权校验跳过（hub 不可达）: {}", e.getMessage());
            return;
        }
        for (DbConnectionService.ActiveDbConn c : conns) {
            if (!verdict.getOrDefault(c.serverKey(), false)) {
                log.warn("数据库连接授权已失效，强制断开: workspace={}, connKey={}, serverKey={}",
                        c.workspaceId(), c.connKey(), c.serverKey());
                db.disconnect(c.workspaceId(), c.connKey());
            }
        }
    }

    /** hub 返回 {serverKey: bool}；解析异常上抛由调用方按「hub 不可达」处理（保持现状不断开） */
    private static Map<String, Boolean> parse(String body) throws Exception {
        Map<String, Boolean> out = new HashMap<>();
        var fields = MAPPER.readTree(body).fields();
        while (fields.hasNext()) {
            var e = fields.next();
            out.put(e.getKey(), e.getValue().asBoolean());
        }
        return out;
    }

    private boolean cloudConfigured() {
        return cloudProperties.getHubUrl() != null && !cloudProperties.getHubUrl().isBlank()
                && cloudProperties.getAppKey() != null && !cloudProperties.getAppKey().isBlank();
    }
}
