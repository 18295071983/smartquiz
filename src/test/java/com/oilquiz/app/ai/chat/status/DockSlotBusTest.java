package com.oilquiz.app.ai.chat.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * dock 插槽总线的契约（DOCK-SLOTS，2026-11-13）。
 *
 * <p>这些约束直接决定输入区上方的观感：同 id 必须覆盖（否则后台任务每来一条就多一行）、
 * 必须按 priority 排序（瞬时状态在上、目标在中间）、注册即回放（切页回来不丢）。</p>
 */
public class DockSlotBusTest {

    @Before
    public void setUp() {
        DockSlotBus.clear();
    }

    @After
    public void tearDown() {
        DockSlotBus.clear();
    }

    private static DockSlotBus.Slot slot(String id, int prio, String title) {
        return new DockSlotBus.Slot(id, prio, title, "", "", false);
    }

    @Test
    public void ordersByPriorityAscending() {
        DockSlotBus.update(slot("job", 40, "任务"));
        DockSlotBus.update(slot("compaction", 10, "压缩"));

        List<DockSlotBus.Slot> list = DockSlotBus.list();

        assertEquals(2, list.size());
        assertEquals("compaction", list.get(0).id);
        assertEquals("job", list.get(1).id);
    }

    @Test
    public void sameIdOverwritesInsteadOfAppending() {
        DockSlotBus.update(slot("job", 40, "下载 A"));
        DockSlotBus.update(slot("job", 40, "下载 B"));

        List<DockSlotBus.Slot> list = DockSlotBus.list();

        assertEquals("同 id 只能有一条", 1, list.size());
        assertEquals("下载 B", list.get(0).title);
    }

    @Test
    public void removeTakesEffectAndIsIdempotent() {
        DockSlotBus.update(slot("compaction", 10, "压缩"));
        DockSlotBus.remove("compaction");
        DockSlotBus.remove("compaction");

        assertTrue(DockSlotBus.list().isEmpty());
    }

    @Test
    public void listenerReceivesUpdatesAndImmediateReplay() {
        AtomicInteger calls = new AtomicInteger();
        DockSlotBus.Listener l = slots -> calls.incrementAndGet();

        DockSlotBus.addListener(l);
        assertEquals("注册即回放一次", 1, calls.get());

        DockSlotBus.update(slot("job", 40, "任务"));
        assertEquals(2, calls.get());

        DockSlotBus.remove("job");
        assertEquals(3, calls.get());

        DockSlotBus.removeListener(l);
        DockSlotBus.update(slot("job", 40, "再来"));
        assertEquals("注销后不再收到", 3, calls.get());
    }

    @Test
    public void blankIdIsIgnored() {
        DockSlotBus.update(new DockSlotBus.Slot("", 10, "x", "", "", false));
        DockSlotBus.update(null);
        assertTrue(DockSlotBus.list().isEmpty());
    }

    @Test
    public void clearEmptiesEverything() {
        DockSlotBus.update(slot("a", 10, "a"));
        DockSlotBus.update(slot("b", 20, "b"));
        DockSlotBus.clear();
        assertTrue(DockSlotBus.list().isEmpty());
        assertFalse(DockSlotBus.list().iterator().hasNext());
    }
}
