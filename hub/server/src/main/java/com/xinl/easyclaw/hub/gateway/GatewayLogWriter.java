package com.xinl.easyclaw.hub.gateway;

import com.xinl.easyclaw.hub.entity.AppKeyEntity;
import com.xinl.easyclaw.hub.entity.GatewayLogEntity;
import com.xinl.easyclaw.hub.repository.AppKeyRepository;
import com.xinl.easyclaw.hub.repository.GatewayLogRepository;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 网关详单异步落盘（设计 §4.2「写日志不阻塞转发」）：单线程守护队列，响应完成后提交；
 * 落库失败仅告警不影响主链路。同线程顺带回写 appkey.last_used_at。
 * 正文截断在提交前完成（{@link #truncate}）。
 */
@Component
public class GatewayLogWriter {

    private static final Logger log = LoggerFactory.getLogger(GatewayLogWriter.class);

    /** 单条正文（request/response）入库上限，超限截断并标注（设计 §4.2 预留拆分对象存储）。 */
    static final int BODY_LIMIT = 64 * 1024;

    private final GatewayLogRepository logs;
    private final AppKeyRepository appKeys;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gateway-log-writer");
        t.setDaemon(true);
        return t;
    });

    public GatewayLogWriter(GatewayLogRepository logs, AppKeyRepository appKeys) {
        this.logs = logs;
        this.appKeys = appKeys;
    }

    public void write(GatewayLogEntity entity) {
        executor.submit(() -> {
            try {
                logs.save(entity);
                touchLastUsed(entity.getAppKeyId());
            } catch (Exception e) {
                log.warn("[gateway] 详单落库失败 appKeyId={} model={}: {}", entity.getAppKeyId(),
                        entity.getModel(), e.getMessage());
            }
        });
    }

    private void touchLastUsed(Long appKeyId) {
        if (appKeyId == null) {
            return;
        }
        try {
            appKeys.findById(appKeyId).ifPresent(k -> {
                k.setLastUsedAt(Instant.now());
                appKeys.save(k);
            });
        } catch (Exception e) {
            log.warn("[gateway] last_used_at 回写失败 appKeyId={}: {}", appKeyId, e.getMessage());
        }
    }

    public static String truncate(String body) {
        if (body == null) {
            return null;
        }
        return body.length() <= BODY_LIMIT ? body : body.substring(0, BODY_LIMIT) + "...[truncated]";
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
