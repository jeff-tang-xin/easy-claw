package com.xinl.easyclaw.agent.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * {@link BaseStore} 的文件版实现 —— team 任务板/邮箱/成员表的持久化底座。
 * <p>
 * harness 自带的 {@code InMemoryStore} 重启丢状态：team 任务板上一轮领到一半的任务、
 * 邮箱未读消息在应用重启后全部蒸发，成员会话恢复后无从知晓之前进行到哪。本实现把
 * 每个 item 落为 workspace 下的一个 JSON 文件（{@code {root}/{namespace...}/{key}.json}，
 * 内容 {@code {"v":版本号,"value":{...}}}），重启后 team 状态完整保留。
 * <p>
 * <b>CAS 语义</b>：与接口契约一致 —— {@code putIfVersion} 仅在当前存储版本等于
 * {@code expectedVersion} 时写入（{@code 0} = 仅当 key 不存在时创建）。单实例部署下
 * 用 per-key synchronized 保证读-比-写的原子性；版本号随每次成功写入单调递增，
 * 与 {@code StoreItem.version()} 的契约一致（从 1 起）。
 * <p>
 * <b>读路径走内存缓存</b>：任务板在 team 协作中被高频轮询（listClaimableTasks），
 * 每次都打盘会成为回合延迟的放大器。缓存以 namespace 为分片，写操作同步更新缓存
 * 与文件，读操作缓存未命中时回源加载该 namespace 全量。
 */
@Component
public class FileBaseStore implements BaseStore {

    private static final Logger log = LoggerFactory.getLogger(FileBaseStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path root;
    private final Object ioLock = new Object();
    /** namespace（join 后）→ 该 namespace 下的 items 缓存 */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, StoreItem>> cache =
            new ConcurrentHashMap<>();

    public FileBaseStore(
            @Value("${easyclaw.team.store-root:}") String configuredRoot) {
        this.root = configuredRoot == null || configuredRoot.isBlank()
                ? null
                : Path.of(configuredRoot);
    }

    /** 用运行时 workspace 目录初始化存储根（WorkspaceManager 拿到 workspace 路径后调用）。 */
    public FileBaseStore(Path workspaceRoot) {
        this.root = workspaceRoot.resolve(".team-store");
    }

    private Path rootDir() {
        Path r = root;
        if (r == null) {
            // 兜底：未配置时落到用户目录，保证组件可用（正常装配路径会显式传入 workspace）
            r = Path.of(System.getProperty("user.home"), ".easyClaw", "team-store");
        }
        try {
            Files.createDirectories(r);
        } catch (IOException e) {
            throw new IllegalStateException("team store 根目录创建失败: " + r, e);
        }
        return r;
    }

    private Path fileOf(List<String> namespace, String key) {
        Path dir = rootDir();
        for (String seg : namespace) {
            dir = dir.resolve(sanitize(seg));
        }
        return dir.resolve(sanitize(key) + ".json");
    }

    private static String sanitize(String seg) {
        String s = seg.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        return s.isBlank() ? "_" : s;
    }

    private String nsKey(List<String> namespace) {
        return String.join("/", namespace);
    }

    private record Persisted(long v, Map<String, Object> value) {}

    private StoreItem load(Path file, String key) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        Persisted p = MAPPER.readValue(json, Persisted.class);
        return new StoreItem(key, p.value(), p.v());
    }

    private void save(Path file, String key, Map<String, Object> value, long version)
            throws IOException {
        Files.createDirectories(file.getParent());
        String json = MAPPER.writeValueAsString(new Persisted(version, value));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        String ns = nsKey(namespace);
        ConcurrentHashMap<String, StoreItem> shard = cache.get(ns);
        if (shard != null) {
            StoreItem hit = shard.get(key);
            if (hit != null) {
                return hit;
            }
        }
        synchronized (ioLock) {
            try {
                StoreItem item = load(fileOf(namespace, key), key);
                if (item != null) {
                    cache.computeIfAbsent(ns, k -> new ConcurrentHashMap<>()).put(key, item);
                }
                return item;
            } catch (IOException e) {
                log.warn("[team-store] 读取失败: ns={}, key={}, err={}", ns, key, e.toString());
                return null;
            }
        }
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        // 无条件写：版本 = 当前版本 + 1（不存在则从 1 起）
        StoreItem cur = get(namespace, key);
        long next = (cur == null ? 0L : cur.version()) + 1;
        writeThrough(namespace, key, value, next);
    }

    @Override
    public boolean putIfVersion(List<String> namespace, String key, Map<String, Object> value,
                                long expectedVersion) {
        String ns = nsKey(namespace);
        synchronized (ioLock) {
            try {
                Path file = fileOf(namespace, key);
                StoreItem cur = load(file, key);
                long curVersion = cur == null ? 0L : cur.version();
                if (expectedVersion == 0L) {
                    // create-if-absent
                    if (cur != null) {
                        return false;
                    }
                } else if (curVersion != expectedVersion) {
                    return false;
                }
                long next = curVersion + 1;
                save(file, key, value, next);
                StoreItem item = new StoreItem(key, value, next);
                cache.computeIfAbsent(ns, k -> new ConcurrentHashMap<>()).put(key, item);
                return true;
            } catch (IOException e) {
                log.warn("[team-store] CAS 写入失败: ns={}, key={}, err={}", ns, key, e.toString());
                return false;
            }
        }
    }

    private void writeThrough(List<String> namespace, String key, Map<String, Object> value,
                              long version) {
        String ns = nsKey(namespace);
        synchronized (ioLock) {
            try {
                save(fileOf(namespace, key), key, value, version);
                cache.computeIfAbsent(ns, k -> new ConcurrentHashMap<>())
                        .put(key, new StoreItem(key, value, version));
            } catch (IOException e) {
                log.warn("[team-store] 写入失败: ns={}, key={}, err={}", ns, key, e.toString());
            }
        }
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        String ns = nsKey(namespace);
        ConcurrentHashMap<String, StoreItem> shard = cache.get(ns);
        if (shard == null) {
            loadNamespace(namespace, ns);
            shard = cache.get(ns);
        }
        List<StoreItem> items = new ArrayList<>();
        if (shard != null) {
            shard.values().stream()
                    .sorted(Comparator.comparing(StoreItem::key))
                    .skip(Math.max(offset, 0))
                    .limit(limit > 0 ? limit : Long.MAX_VALUE)
                    .forEach(items::add);
        }
        return items;
    }

    /** 回源加载一个 namespace 下的全部 item（目录扫描）。 */
    private void loadNamespace(List<String> namespace, String ns) {
        synchronized (ioLock) {
            if (cache.containsKey(ns)) {
                return;
            }
            ConcurrentHashMap<String, StoreItem> shard = new ConcurrentHashMap<>();
            Path dir = rootDir();
            for (String seg : namespace) {
                dir = dir.resolve(sanitize(seg));
            }
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    files.filter(f -> f.getFileName().toString().endsWith(".json"))
                            .forEach(f -> {
                                String key = f.getFileName().toString()
                                        .substring(0, f.getFileName().toString().length() - 5);
                                try {
                                    StoreItem item = load(f, key);
                                    if (item != null) {
                                        shard.put(key, item);
                                    }
                                } catch (IOException e) {
                                    log.warn("[team-store] namespace 回源跳过坏文件: {}, err={}",
                                            f.getFileName(), e.toString());
                                }
                            });
                } catch (IOException e) {
                    log.warn("[team-store] namespace 扫描失败: ns={}, err={}", ns, e.toString());
                }
            }
            cache.put(ns, shard);
        }
    }

    @Override
    public void delete(List<String> namespace, String key) {
        String ns = nsKey(namespace);
        synchronized (ioLock) {
            ConcurrentHashMap<String, StoreItem> shard = cache.get(ns);
            if (shard != null) {
                shard.remove(key);
            }
            try {
                Files.deleteIfExists(fileOf(namespace, key));
            } catch (IOException e) {
                log.warn("[team-store] 删除失败: ns={}, key={}, err={}", ns, key, e.toString());
            }
        }
    }
}
