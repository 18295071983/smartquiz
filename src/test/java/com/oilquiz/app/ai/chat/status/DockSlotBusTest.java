package com.oilquiz.app.ai.chat.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
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

    /**
     * 切会话只清插件槽位（2026-11-16）：上个会话的插件槽位必须走，
     * 本机写入的 local 与跨会话都该看到的 pinned 必须留。
     */
    @Test
    public void clearPluginSlotsKeepsLocalAndPinned() {
        DockSlotBus.update(new DockSlotBus.Slot("job", 40, "上个会话的任务", "", "", false,
                "display", "state", false, 0, 1L, 0L, "plugin"));
        DockSlotBus.updateLocal(new DockSlotBus.Slot("local-progress", 10, "本机进度", "", "", false,
                "progress", "state", false, 0, 1L, 0L, "local"));
        DockSlotBus.update(new DockSlotBus.Slot("goal", 30, "目标", "", "", false,
                "display", "state", true, 0, 1L, 0L, "plugin"));

        DockSlotBus.clearPluginSlots();

        List<DockSlotBus.Slot> left = DockSlotBus.list();
        assertEquals("只该剩下 local + pinned", 2, left.size());
        boolean hasJob = false;
        boolean hasLocal = false;
        boolean hasGoal = false;
        for (DockSlotBus.Slot s : left) {
            if ("job".equals(s.id)) hasJob = true;
            if ("local-progress".equals(s.id)) hasLocal = true;
            if ("goal".equals(s.id)) hasGoal = true;
        }
        assertFalse("上个会话的插件槽位应被清掉", hasJob);
        assertTrue("local 槽位不该被清", hasLocal);
        assertTrue("pinned 槽位不该被清", hasGoal);
    }

    /** 清槽位不能把版本水位一起清掉：旧会话的迟到帧不能复活。 */
    @Test
    public void clearPluginSlotsKeepsRevWatermark() {
        DockSlotBus.update(new DockSlotBus.Slot("job", 40, "v5", "", "", false,
                "display", "state", false, 0, 5L, 0L, "plugin"));
        DockSlotBus.clearPluginSlots();
        // 旧帧（rev 比清掉的那条小）应被水位挡住
        DockSlotBus.update(new DockSlotBus.Slot("job", 40, "stale", "", "", false,
                "display", "state", false, 0, 3L, 0L, "plugin"));
        assertTrue("迟到帧不该复活旧槽位", DockSlotBus.list().isEmpty());
    }

    @Test
    public void ordersByPriorityAscending() {        DockSlotBus.update(slot("job", 40, "任务"));
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

    // ===== §22（2026-11-15）：上限 + 溢出淘汰 =====

    private static boolean hasSlot(List<DockSlotBus.Slot> list, String id) {
        for (DockSlotBus.Slot s : list) {
            if (s.id.equals(id)) return true;
        }
        return false;
    }

    private static DockSlotBus.Slot pinned(String id, int prio, String title) {
        return new DockSlotBus.Slot(id, prio, title, "", "connect", false,
                "display", "state", true, 0);
    }

    @Test
    public void capsSlotsAtMaxAndEvictsHighestPriorityOldestFirst() {
        // 16 条 p5（后台档，最旧）→ 再塞 4 条 p0（打断式）：被淘汰的必须是 p5 里最旧的 4 条
        for (int i = 0; i < DockSlotBus.MAX_SLOTS; i += 1) {
            DockSlotBus.update(slot("bg" + i, SlotPriorities.P5, "后台 " + i));
        }
        assertEquals(DockSlotBus.MAX_SLOTS, DockSlotBus.list().size());

        for (int i = 0; i < 4; i += 1) {
            DockSlotBus.update(slot("urgent" + i, SlotPriorities.P0, "审批 " + i));
        }

        List<DockSlotBus.Slot> list = DockSlotBus.list();
        assertEquals("总线不超上限", DockSlotBus.MAX_SLOTS, list.size());
        assertEquals("累计淘汰 4 条", 4, DockSlotBus.evictedCount());
        for (int i = 0; i < 4; i += 1) {
            assertTrue("p0 是打断式，必须保留: urgent" + i, hasSlot(list, "urgent" + i));
            assertFalse("同档最旧的先走: bg" + i, hasSlot(list, "bg" + i));
        }
        for (int i = 4; i < DockSlotBus.MAX_SLOTS; i += 1) {
            assertTrue("较新的 p5 保留: bg" + i, hasSlot(list, "bg" + i));
        }
    }

    @Test
    public void lowerPriorityNumberBeatsOlderTimestampOnOverflow() {
        // 交替插入：数字大的（p5）即使在后面才更新，也必须先于 p3 被淘汰
        DockSlotBus.update(slot("bg", SlotPriorities.P5, "后台"));
        for (int i = 0; i < DockSlotBus.MAX_SLOTS - 1; i += 1) {
            DockSlotBus.update(slot("stream" + i, SlotPriorities.P3, "压缩 " + i));
        }
        DockSlotBus.update(slot("bg2", SlotPriorities.P5, "后台 2"));

        List<DockSlotBus.Slot> list = DockSlotBus.list();
        assertEquals(DockSlotBus.MAX_SLOTS, list.size());
        assertFalse("p5 比任何 p3 都先被淘汰", hasSlot(list, "bg"));
        for (int i = 0; i < DockSlotBus.MAX_SLOTS - 1; i += 1) {
            assertTrue("p3 全保留: stream" + i, hasSlot(list, "stream" + i));
        }
        assertTrue("后插入的 p5 也保留（只淘汰了一条）", hasSlot(list, "bg2"));
    }

    @Test
    public void pinnedSlotsAreNeverEvicted() {
        DockSlotBus.update(pinned("pinned", SlotPriorities.P5, "固定状态"));
        for (int i = 0; i < 20; i += 1) {
            DockSlotBus.update(slot("tmp" + i, SlotPriorities.P5, "临时 " + i));
        }

        List<DockSlotBus.Slot> list = DockSlotBus.list();
        assertEquals(DockSlotBus.MAX_SLOTS, list.size());
        assertTrue("pinned 比所有临时槽都旧，但永不被淘汰", hasSlot(list, "pinned"));
        assertFalse(hasSlot(list, "tmp0"));
        assertTrue("最新的临时槽留着", hasSlot(list, "tmp19"));
    }

    @Test
    public void allPinnedOverflowIsLeftAlone() {
        for (int i = 0; i < DockSlotBus.MAX_SLOTS + 3; i += 1) {
            DockSlotBus.update(pinned("pin" + i, SlotPriorities.P5, "固定 " + i));
        }
        assertEquals("全是 pinned 时宁可超限也不挤掉固定状态",
                DockSlotBus.MAX_SLOTS + 3, DockSlotBus.list().size());
        assertEquals(0, DockSlotBus.evictedCount());
    }

    @Test
    public void evictedSlotIsNotResurrectedByReplayedFrame() {
        for (int i = 0; i < DockSlotBus.MAX_SLOTS + 1; i += 1) {
            DockSlotBus.update(versioned("cap" + i, SlotPriorities.P5, "后台 " + i, 1L));
        }
        List<DockSlotBus.Slot> list = DockSlotBus.list();
        assertEquals(DockSlotBus.MAX_SLOTS, list.size());
        assertFalse("同档最旧的 cap0 先被淘汰", hasSlot(list, "cap0"));

        // 补发/快照可能再送一次被淘汰的那条（同 rev）：不能复活，否则淘汰与补发互相打架
        DockSlotBus.update(versioned("cap0", SlotPriorities.P5, "复活", 1L));
        assertEquals("淘汰是粘性的：同 rev 补发不重占名额",
                DockSlotBus.MAX_SLOTS, DockSlotBus.list().size());
        assertFalse(hasSlot(DockSlotBus.list(), "cap0"));
    }

    // ===== ④（2026-11-15）：kind/progress/actions 存取 =====

    @Test
    public void actionsAndProgressSurviveUpdateAndWithSource() {
        List<SlotAction> actions = Arrays.asList(
                new SlotAction("permission", "调整", SlotAction.STYLE_PRIMARY),
                new SlotAction("jobs", "任务", null));
        DockSlotBus.update(new DockSlotBus.Slot("permission", SlotPriorities.P2,
                "权限", "完全访问", "permission", true,
                SlotKind.ACTION, "state", false, 0, 0, actions));

        DockSlotBus.Slot action = DockSlotBus.list().get(0);
        assertEquals(SlotKind.ACTION, action.kind);
        assertEquals(2, action.actions.size());
        assertEquals("permission", action.actions.get(0).id);
        assertEquals(SlotAction.STYLE_PRIMARY, action.actions.get(0).style);
        assertEquals(SlotAction.STYLE_PLAIN, action.actions.get(1).style);
        assertEquals("progress 只在 progress kind 有意义", 0, action.progress);

        DockSlotBus.Slot local = action.withSource("local");
        assertEquals("withSource 不能丢 actions", actions, local.actions);
        assertEquals("local", local.source);

        DockSlotBus.update(new DockSlotBus.Slot("compaction", SlotPriorities.P3,
                "压缩中", "", "", false, SlotKind.PROGRESS, "task", false, 0, 37, null));
        DockSlotBus.Slot progress = null;
        for (DockSlotBus.Slot s : DockSlotBus.list()) {
            if ("compaction".equals(s.id)) progress = s;
        }
        assertTrue("应能查到 progress 槽", progress != null);
        assertEquals(SlotKind.PROGRESS, progress.kind);
        assertEquals(37, progress.progress);
        assertTrue(progress.actions.isEmpty());
    }
}
