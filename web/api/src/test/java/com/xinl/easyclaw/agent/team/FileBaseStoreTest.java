package com.xinl.easyclaw.agent.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileBaseStore 的 CAS 语义与持久化验证。
 * 对齐 harness BaseStore 契约：version 从 1 起单调递增；putIfVersion(0)=create-if-absent。
 */
class FileBaseStoreTest {

    @TempDir
    Path tmp;

    private FileBaseStore newStore() {
        return new FileBaseStore(tmp);
    }

    @Test
    void putThenGetReturnsItemWithIncrementedVersion() {
        FileBaseStore store = newStore();
        store.put(List.of("teams", "ws1", "t1", "tasks"), "task-1", Map.of("state", "PENDING"));
        var item = store.get(List.of("teams", "ws1", "t1", "tasks"), "task-1");
        assertNotNull(item);
        assertEquals(1L, item.version());
        assertEquals("PENDING", item.value().get("state"));
    }

    @Test
    void putIfVersionZeroIsCreateIfAbsent() {
        FileBaseStore store = newStore();
        List<String> ns = List.of("teams", "ws1", "t1", "tasks");
        assertTrue(store.putIfVersion(ns, "task-1", Map.of("v", "first"), 0L),
                "expectedVersion=0 且 key 不存在时应创建成功");
        assertFalse(store.putIfVersion(ns, "task-1", Map.of("v", "second"), 0L),
                "key 已存在时 expectedVersion=0 应失败（create-if-absent）");
        assertEquals("first", store.get(ns, "task-1").value().get("v"));
    }

    @Test
    void putIfVersionCasConflictAndSuccess() {
        FileBaseStore store = newStore();
        List<String> ns = List.of("teams", "ws1", "t1", "tasks");
        store.put(ns, "task-1", Map.of("state", "PENDING"));
        // 版本 1 → CAS(1) 成功 → 版本 2
        assertTrue(store.putIfVersion(ns, "task-1", Map.of("state", "IN_PROGRESS"), 1L));
        assertEquals(2L, store.get(ns, "task-1").version());
        // 旧版本 CAS 冲突
        assertFalse(store.putIfVersion(ns, "task-1", Map.of("state", "STALE"), 1L));
        assertEquals("IN_PROGRESS", store.get(ns, "task-1").value().get("state"));
    }

    @Test
    void stateSurvivesStoreRestart() {
        List<String> ns = List.of("teams", "ws1", "t1", "tasks");
        FileBaseStore first = newStore();
        first.put(ns, "task-1", Map.of("state", "IN_PROGRESS", "owner", "w1"));
        first.putIfVersion(ns, "task-2", Map.of("state", "PENDING"), 0L);
        // 模拟应用重启：全新实例读同一目录
        FileBaseStore second = newStore();
        var t1 = second.get(ns, "task-1");
        assertNotNull(t1, "重启后任务应可读（InMemoryStore 会丢）");
        assertEquals("IN_PROGRESS", t1.value().get("state"));
        assertEquals("w1", t1.value().get("owner"));
        assertEquals(1L, t1.version());
        assertNotNull(second.get(ns, "task-2"));
    }

    @Test
    void searchReturnsNamespaceItemsInKeyOrder() {
        FileBaseStore store = newStore();
        List<String> ns = List.of("teams", "ws1", "t1", "tasks");
        for (int i = 1; i <= 5; i++) {
            store.put(ns, "task-" + i, Map.of("seq", i));
        }
        List<io.agentscope.harness.agent.filesystem.remote.store.StoreItem> items = store.search(ns, 3, 0);
        assertEquals(3, items.size());
        assertEquals("task-1", items.get(0).key());
        // offset 翻页
        List<io.agentscope.harness.agent.filesystem.remote.store.StoreItem> page2 = store.search(ns, 3, 3);
        assertEquals(2, page2.size());
        assertEquals("task-4", page2.get(0).key());
        // 其他 namespace 不可见
        assertTrue(store.search(List.of("teams", "ws2", "t1", "tasks"), 10, 0).isEmpty());
    }

    @Test
    void deleteRemovesItemAndPersists() {
        FileBaseStore store = newStore();
        List<String> ns = List.of("teams", "ws1", "t1", "tasks");
        store.put(ns, "task-1", Map.of("state", "PENDING"));
        store.delete(ns, "task-1");
        assertNull(store.get(ns, "task-1"));
        // 重启后仍不存在
        assertNull(newStore().get(ns, "task-1"));
    }
}
