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
        // clear() 现在保留 pinned（§20），测试要彻底清干净，避免静态总线状态串到别的用例
        for (DockSlotBus.Slot s : DockSlotBus.list()) DockSlotBus.remove(s.id);
        DockSlotBus.clear();
    }

    private static DockSlotBus.Slot slot(String id, int prio, String title) {
        return new DockSlotBus.Slot(id, prio, title, "", "", false);
    }

    private static DockSlotBus.Slot versioned(String id, int prio, String title, long rev) {
        return new DockSlotBus.Slot(id, prio, title, "", "", false,
                "display", "state", false, 0, rev, 0L, "plugin");
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

    // ===== §20（2026-11-15）：rev / TTL / 单一写者 =====

    @Test
    public void revMustIncreaseWithinSameId() {
        DockSlotBus.update(versioned("revjob", 40, "A", 1L));
        DockSlotBus.update(versioned("revjob", 40, "B", 2L));
        assertEquals(1, DockSlotBus.list().size());
        assertEquals("B", DockSlotBus.list().get(0).title);
        assertEquals(2L, DockSlotBus.list().get(0).rev);

        DockSlotBus.update(versioned("revjob", 40, "迟到的 A", 1L));
        assertEquals("回退 rev 直接丢弃", "B", DockSlotBus.list().get(0).title);

        DockSlotBus.update(versioned("revjob", 40, "重复的 B", 2L));
        assertEquals("同 rev 视为重复", "B", DockSlotBus.list().get(0).title);
    }

    @Test
    public void legacySlotWithoutRevStillOverwrites() {
        // 旧插件/本地写入 rev=0：保持"同 id 覆盖"的旧语义，升级不能把行为改坏
        DockSlotBus.update(slot("legacyjob", 40, "A"));
        DockSlotBus.update(slot("legacyjob", 40, "B"));
        assertEquals("B", DockSlotBus.list().get(0).title);
    }

    @Test
    public void sweepRemovesExpiredButKeepsStableAndPinned() {
        long now = System.currentTimeMillis();
        DockSlotBus.update(new DockSlotBus.Slot("expiring", 40, "任务", "", "", false,
                "display", "state", false, 0, 1L, now + 10_000L, "plugin"));
        DockSlotBus.update(new DockSlotBus.Slot("stable", 40, "目标", "", "", false,
                "display", "state", false, 0, 1L, 0L, "plugin"));
        DockSlotBus.update(new DockSlotBus.Slot("pinned", 6, "通道落后", "", "connect", false,
                "display", "state", true, 0, 1L, 0L, "plugin"));

        assertEquals(0, DockSlotBus.sweepExpired(now + 9_999L));
        assertEquals(1, DockSlotBus.sweepExpired(now + 10_001L));

        List<DockSlotBus.Slot> left = DockSlotBus.list();
        assertEquals(2, left.size());
        for (DockSlotBus.Slot s : left) {
            assertFalse("expiring 应被清掉", "expiring".equals(s.id));
        }
        assertEquals("pinned", left.get(0).id);
        assertEquals("stable", left.get(1).id);
    }

    @Test
    public void clearKeepsPinnedSlotsForReconnect() {
        long now = System.currentTimeMillis();
        DockSlotBus.update(new DockSlotBus.Slot("pinned", 6, "通道落后", "", "connect", false,
                "display", "state", true, 0, 1L, now + 60_000L, "plugin"));
        DockSlotBus.update(slot("job", 40, "任务"));

        DockSlotBus.clear();

        assertEquals("断连只清瞬时态，pinned 留着", 1, DockSlotBus.list().size());
        assertEquals("pinned", DockSlotBus.list().get(0).id);
    }

    @Test
    public void localFallbackYieldsToPluginWithinThreeSeconds() {
        DockSlotBus.update(versioned("compaction", 10, "插件压缩", 3L));
        DockSlotBus.updateLocal(new DockSlotBus.Slot(
                "compaction", 10, "本地压缩", "", "", true));

        assertEquals("插件 3s 内写过，同一事实只能有一行（且是插件那行）",
                "插件压缩", DockSlotBus.list().get(0).title);
    }
}
