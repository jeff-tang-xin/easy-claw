package com.xinl.easyclaw.memory.flush;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FlushSlicePlanner} 纯函数切片语义：窗口有界、游标单调推进、超界重置。 */
class FlushSlicePlannerTest {

    @Test
    void 空上下文返回空切片() {
        FlushSlicePlanner.Slice s = FlushSlicePlanner.plan(0, 0, 10, 5);
        assertTrue(s.isEmpty());
        assertEquals(0, s.to());
    }

    @Test
    void 增量未超窗全取并推进到末尾() {
        // 8 条新消息，窗口 10 → 全取，背景 0（游标在 0，无前置可带）
        FlushSlicePlanner.Slice s = FlushSlicePlanner.plan(8, 0, 10, 5);
        assertEquals(0, s.from());
        assertEquals(8, s.to());
        assertEquals(0, s.backgroundFrom());
        assertEquals(8, s.newCount());
    }

    @Test
    void 增量超窗只取最早一批剩余留待下轮() {
        // 25 条新消息，窗口 10 → 取 [0,10)，下轮从 10 继续
        FlushSlicePlanner.Slice s1 = FlushSlicePlanner.plan(25, 0, 10, 5);
        assertEquals(0, s1.from());
        assertEquals(10, s1.to());
        assertEquals(0, s1.backgroundFrom());

        FlushSlicePlanner.Slice s2 = FlushSlicePlanner.plan(25, s1.to(), 10, 5);
        assertEquals(10, s2.from());
        assertEquals(20, s2.to());
        assertEquals(5, s2.backgroundFrom(), "窗口前携带 5 条背景");

        FlushSlicePlanner.Slice s3 = FlushSlicePlanner.plan(25, s2.to(), 10, 5);
        assertEquals(20, s3.from());
        assertEquals(25, s3.to(), "最后一批不足窗口也全取");
    }

    @Test
    void 游标追平上下文返回空切片() {
        FlushSlicePlanner.Slice s = FlushSlicePlanner.plan(10, 10, 10, 5);
        assertTrue(s.isEmpty());
    }

    @Test
    void 压缩后游标越界重置为最后窗口起点() {
        // 压缩前游标 30，压缩后上下文只剩 8 条 → 重置提取最后 min(8, window) 条
        FlushSlicePlanner.Slice s = FlushSlicePlanner.plan(8, 30, 10, 5);
        assertEquals(0, s.from());
        assertEquals(8, s.to());

        // 上下文剩 12 条、窗口 10 → 从 2 开始，宁重复不遗漏
        FlushSlicePlanner.Slice s2 = FlushSlicePlanner.plan(12, 30, 10, 5);
        assertEquals(2, s2.from());
        assertEquals(12, s2.to());
    }

    @Test
    void 背景下标不越界() {
        FlushSlicePlanner.Slice s = FlushSlicePlanner.plan(10, 3, 10, 5);
        assertEquals(0, s.backgroundFrom(), "游标前不足 background 条时钳到 0");
        assertEquals(3, s.from());
    }
}
