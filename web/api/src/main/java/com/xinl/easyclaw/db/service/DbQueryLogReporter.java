package com.xinl.easyclaw.db.service;

import com.xinl.easyclaw.config.CloudProperties;
import com.xinl.easyclaw.config.HubSpokeClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 数据库查询审计上报器（spoke → hub 异步批量转报，V30）。
 * <p>
 * AI（db_query 工具）与用户（DbPage 控制台）执行的查询统一经 {@link #enqueue} 入队，
 * 单后台守护线程阻塞等待队列（{@code poll(500ms)}），有记录即凑批（单批最多 200 条）
 * 上报 POST {@code /api/spoke/db-query-logs}（appkey Bearer 由 {@link HubSpokeClient}
 * 内置；operator 由 hub 从 appkey 补全，spoke 不传）。
 * <p>
 * 失败语义与 {@link com.xinl.easyclaw.ops.service.OpsCommandLogReporter} 一致：
 * 上报失败重试 1 次后丢弃并 warn——审计日志允许有损，绝不阻塞查询执行、不无限堆积
 * （队列超 5000 条丢最旧）。local 模式（hubUrl/appKey 缺失）入队即静默丢弃。
 * <p>
 * V30.1：以 {@link BlockingQueue}（容量 = 队列上限）替代 ConcurrentLinkedQueue +
 * sleep 轮询——事件驱动、关停即时响应；「队列满丢最旧」由 offer/poll 单循环完成，
 * 消除原 {@code size() >= MAX} check-then-act 微竞态；{@code size()} O(n) 扫描随之消失。
 */
@Component
public class DbQueryLogReporter {

    private static final Logger log = LoggerFactory.getLogger(DbQueryLogReporter.class);
    /** hub 上报端点 */
    private static final String REPORT_PATH = "/api/spoke/db-query-logs";
    /** 单批最多上报条数 */
    private static final int MAX_BATCH = 200;
    /** 队列上限：超过即丢最旧（hub 不可达时不无限堆积） */
    private static final int MAX_QUEUE = 5000;
    /** 队列空转等待时长（毫秒）：有记录即时凑批，无记录每 500ms 醒一次检查关停 */
    private static final long FLUSH_INTERVAL_MS = 500;

    /** 单条待上报查询日志（executedAt = 入队时刻，ISO-8601） */
    private record PendingLog(String serverKey, String serverName, String dbType, String host,
                              String databaseName, String sqlText, String source, String executedAt) {
    }

    private final HubSpokeClient hub;
    private final CloudProperties cloudProperties;
    private final BlockingQueue<PendingLog> queue = new ArrayBlockingQueue<>(MAX_QUEUE);
    private final Thread worker;

    public DbQueryLogReporter(HubSpokeClient hub, CloudProperties cloudProperties) {
        this.hub = hub;
        this.cloudProperties = cloudProperties;
        this.worker = new Thread(this::runLoop, "db-query-log-reporter");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 入队一条查询日志（异步批量上报；本方法不抛异常、不阻塞调用方）。
     *
     * @param source 查询来源：{@code ai}（db_query 工具）/ {@code user}（DbPage 控制台）
     */
    public void enqueue(String serverKey, String serverName, String dbType, String host,
                        String databaseName, String sqlText, String source) {
        if (!cloudConfigured()) {
            return; // local 模式无 hub 可报，静默丢弃
        }
        PendingLog entry = new PendingLog(nvl(serverKey), nvl(serverName), nvl(dbType), nvl(host),
                nvl(databaseName), nvl(sqlText), nvl(source), Instant.now().toString());
        while (!queue.offer(entry)) {
            if (queue.poll() == null) {
                break; // 并发下队列恰好被取空，下一轮 offer 必成功
            }
        }
    }

    /** 后台循环：阻塞等首条 → 凑一批（≤MAX_BATCH）上报；中断即退出（@PreDestroy 先 flush 残留） */
    private void runLoop() {
        while (true) {
            try {
                PendingLog first = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<PendingLog> batch = new ArrayList<>();
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH - 1);
                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // flush 内部已兜底捕获，此处防御意外异常导致线程静默退出
                log.warn("db 查询上报线程异常（继续运行）: {}", e.getMessage());
            }
        }
    }

    /** 上报一批；失败重试 1 次后丢弃并 warn */
    private void flush(List<PendingLog> batch) {
        try {
            hub.post(REPORT_PATH, Map.of("logs", toBody(batch)));
        } catch (Exception first) {
            try {
                hub.post(REPORT_PATH, Map.of("logs", toBody(batch)));
            } catch (Exception second) {
                log.warn("db 查询日志上报失败（丢弃 {} 条）: {}", batch.size(), second.getMessage());
            }
        }
    }

    /** 停机前 flush 残留：停工作线程后把队列剩余一次性尽力上报（不再重试） */
    @PreDestroy
    public void flushRemaining() {
        worker.interrupt();
        try {
            worker.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<PendingLog> rest = new ArrayList<>();
        queue.drainTo(rest);
        if (rest.isEmpty()) {
            return;
        }
        try {
            hub.post(REPORT_PATH, Map.of("logs", toBody(rest)));
        } catch (Exception e) {
            log.warn("停机 flush 残留查询日志失败（丢弃 {} 条）: {}", rest.size(), e.getMessage());
        }
    }

    private List<Map<String, String>> toBody(List<PendingLog> batch) {
        List<Map<String, String>> logs = new ArrayList<>(batch.size());
        for (PendingLog p : batch) {
            Map<String, String> item = new HashMap<>();
            item.put("serverKey", p.serverKey());
            item.put("serverName", p.serverName());
            item.put("dbType", p.dbType());
            item.put("host", p.host());
            item.put("databaseName", p.databaseName());
            item.put("sqlText", p.sqlText());
            item.put("source", p.source());
            item.put("executedAt", p.executedAt());
            logs.add(item);
        }
        return logs;
    }

    /** cloud 是否已配置（hubUrl/appKey 均非空）——与 HubSpokeClient.call 的判据一致 */
    private boolean cloudConfigured() {
        String hubUrl = cloudProperties.getHubUrl();
        String appKey = cloudProperties.getAppKey();
        return hubUrl != null && !hubUrl.isBlank() && appKey != null && !appKey.isBlank();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
