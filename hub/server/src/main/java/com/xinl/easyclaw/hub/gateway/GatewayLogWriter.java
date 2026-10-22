package com.xinl.easyclaw.hub.gateway;

import com.xinl.easyclaw.hub.entity.AppKeyEntity;
import com.xinl.easyclaw.hub.entity.GatewayLogEntity;
import com.xinl.easyclaw.hub.repository.AppKeyRepository;
import com.xinl.easyclaw.hub.repository.GatewayLogRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 网关详单异步落盘（设计 §4.2「写日志不阻塞转发」）：单线程守护队列，响应完成后提交；
 * 落库失败仅告警不影响主链路。同线程顺带回写 appkey.last_used_at。
 * 正文截断在提交前完成（{@link #truncate}）。
 * <p>队列有界（{@link #QUEUE_CAPACITY}）：PostgreSQL 落库慢于请求完成速率时丢弃详单并告警，
 * 而不是无界堆积拖垮堆内存——详单可丢，进程不可死。</p>
 */
@Component
public class GatewayLogWriter {

    private static final Logger log = LoggerFactory.getLogger(GatewayLogWriter.class);

    /** 单条正文（request/response）入库上限，超限截断并标注（设计 §4.2 预留拆分对象存储）。 */
    static final int BODY_LIMIT = 64 * 1024;

    /** 待落库详单队列容量：128 条 × 最坏 128KB/条 ≈ 32MB 内存上界。 */
    static final int QUEUE_CAPACITY = 128;

    /** 关停时等待队列刷盘的上限时间。 */
    private static final Duration SHUTDOWN_DRAIN = Duration.ofSeconds(3);

    private final GatewayLogRepository logs;
    private final AppKeyRepository appKeys;
    private final AtomicLong dropped = new AtomicLong();
    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "gateway-log-writer");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    public GatewayLogWriter(GatewayLogRepository logs, AppKeyRepository appKeys) {
        this.logs = logs;
        this.appKeys = appKeys;
    }

    public void write(GatewayLogEntity entity) {
        try {
            executor.submit(() -> {
                try {
                    logs.save(entity);
                    touchLastUsed(entity.getAppKeyId());
                } catch (Exception e) {
                    log.warn("[gateway] 详单落库失败 appKeyId={} model={}: {}", entity.getAppKeyId(),
                            entity.getModel(), e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列满：丢弃详单保主链路（转发不受影响），告警留痕
            log.warn("[gateway] 详单队列已满（容量 {}），丢弃详单 appKeyId={} model={}（累计丢弃 {}）",
                    QUEUE_CAPACITY, entity.getAppKeyId(), entity.getModel(), dropped.incrementAndGet());
        }
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
        try {
            if (!executor.awaitTermination(SHUTDOWN_DRAIN.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("[gateway] 关停时详单队列未在 {}s 内刷完，剩余任务放弃", SHUTDOWN_DRAIN.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
