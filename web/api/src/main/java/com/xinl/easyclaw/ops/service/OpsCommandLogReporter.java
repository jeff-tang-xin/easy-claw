package com.xinl.easyclaw.ops.service;

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
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 运维命令审计上报器（spoke → hub 异步批量转报）。
 * <p>
 * AI（remote_shell 工具）与用户（Web 终端）执行的命令统一经 {@link #enqueue} 入队，
 * 单后台守护线程每 500ms 批量 flush（单批最多 200 条），POST
 * {@code /api/spoke/ops-command-logs}（appkey Bearer 由 {@link HubSpokeClient} 内置；
 * operator 由 hub 从 appkey 补全，spoke 不传）。
 * <p>
 * 失败语义：上报失败重试 1 次后丢弃并 warn——审计日志允许有损，绝不阻塞命令执行、
 * 不无限堆积（队列超 5000 条丢最旧）。local 模式（hubUrl/appKey 缺失）无 hub 可报，
 * 入队即静默丢弃。
 */
@Component
public class OpsCommandLogReporter {

    private static final Logger log = LoggerFactory.getLogger(OpsCommandLogReporter.class);
    /** hub 上报端点 */
    private static final String REPORT_PATH = "/api/spoke/ops-command-logs";
    /** 单批最多上报条数 */
    private static final int MAX_BATCH = 200;
    /** 队列上限：超过即丢最旧（hub 不可达时不无限堆积） */
    private static final int MAX_QUEUE = 5000;
    /** flush 间隔（毫秒） */
    private static final long FLUSH_INTERVAL_MS = 500;

    /** 单条待上报命令日志（executedAt = 入队时刻，ISO-8601） */
    private record PendingLog(String serverKey, String serverName, String host,
                              String command, String source, String executedAt) {
    }

    private final HubSpokeClient hub;
    private final CloudProperties cloudProperties;
    private final ConcurrentLinkedQueue<PendingLog> queue = new ConcurrentLinkedQueue<>();
    private final Thread worker;

    public OpsCommandLogReporter(HubSpokeClient hub, CloudProperties cloudProperties) {
        this.hub = hub;
        this.cloudProperties = cloudProperties;
        this.worker = new Thread(this::runLoop, "ops-command-log-reporter");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 入队一条命令日志（异步批量上报；本方法不抛异常、不阻塞调用方）。
     *
     * @param source 命令来源：{@code ai}（remote_shell 工具）/ {@code user}（Web 终端）
     */
    public void enqueue(String serverKey, String serverName, String host, String command, String source) {
        if (!cloudConfigured()) {
            return; // local 模式无 hub 可报，静默丢弃
        }
        if (queue.size() >= MAX_QUEUE) {
            queue.poll(); // 丢最旧，保新
        }
        queue.add(new PendingLog(nvl(serverKey), nvl(serverName), nvl(host),
                nvl(command), nvl(source), Instant.now().toString()));
    }

    /** 后台循环：定时批量 flush；中断即退出（@PreDestroy 先 flush 残留） */
    private void runLoop() {
        while (true) {
            try {
                Thread.sleep(FLUSH_INTERVAL_MS);
                flushOnce();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // flushOnce 内部已兜底捕获，此处防御意外异常导致线程静默退出
                log.warn("ops 命令上报线程异常（继续运行）: {}", e.getMessage());
            }
        }
    }

    /** 取一批（≤MAX_BATCH）上报；失败重试 1 次后丢弃并 warn */
    private void flushOnce() {
        List<PendingLog> batch = new ArrayList<>();
        PendingLog p;
        while (batch.size() < MAX_BATCH && (p = queue.poll()) != null) {
            batch.add(p);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            hub.post(REPORT_PATH, Map.of("logs", toBody(batch)));
        } catch (Exception first) {
            try {
                hub.post(REPORT_PATH, Map.of("logs", toBody(batch)));
            } catch (Exception second) {
                log.warn("ops 命令日志上报失败（丢弃 {} 条）: {}", batch.size(), second.getMessage());
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
        PendingLog p;
        while ((p = queue.poll()) != null) {
            rest.add(p);
        }
        if (rest.isEmpty()) {
            return;
        }
        try {
            hub.post(REPORT_PATH, Map.of("logs", toBody(rest)));
        } catch (Exception e) {
            log.warn("停机 flush 残留命令日志失败（丢弃 {} 条）: {}", rest.size(), e.getMessage());
        }
    }

    private List<Map<String, String>> toBody(List<PendingLog> batch) {
        List<Map<String, String>> logs = new ArrayList<>(batch.size());
        for (PendingLog p : batch) {
            Map<String, String> item = new HashMap<>();
            item.put("serverKey", p.serverKey());
            item.put("serverName", p.serverName());
            item.put("host", p.host());
            item.put("command", p.command());
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
