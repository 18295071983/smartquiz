package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.status.DockSlotBus;
import com.oilquiz.app.ai.chat.status.SlotPriorities;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 插件插槽帧解析契约（§20 修订，2026-11-15）：rev 透传、ttl 换算、空帧移除、epoch 重置。
 *
 * <p>真机复验发现两件事：phone_ui 先拦了空 title/detail，导致无法移除无 TTL 的槽；
 * 插件重载后 rev 从 1 重来，App 的旧水位把新帧当回退帧丢弃。这里把 App 侧语义钉住。</p>
 */
public class RemoteSlotBridgeTest {

    @Before
    public void setUp() {
        clearAll();
    }

    @After
    public void tearDown() {
        clearAll();
    }

    private static void clearAll() {
        for (DockSlotBus.Slot s : DockSlotBus.list()) DockSlotBus.remove(s.id);
        DockSlotBus.clear();
    }

    private static JSONObject frame(String id, String title, String detail, long rev, long ttlMs)
            throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("detail", detail);
        o.put("rev", rev);
        if (ttlMs > 0) o.put("ttl_ms", ttlMs);
        return o;
    }

    private static JSONObject withEpoch(JSONObject frame, long epoch) throws Exception {
        frame.put("epoch", epoch);
        return frame;
    }

    @Test
    public void emptyFrameRemovesSlotWithNewerRev() throws Exception {
        assertTrue(RemoteSlotBridge.apply(frame("dock-x", "在", "", 1L, 0L)));
        assertEquals(1, DockSlotBus.list().size());

        assertTrue(RemoteSlotBridge.apply(frame("dock-x", "", "", 2L, 0L)));

        assertTrue("空帧（title/detail 都空）必须移除", DockSlotBus.list().isEmpty());
    }

    @Test
    public void staleRemovalDoesNotKillNewerSlot() throws Exception {
        RemoteSlotBridge.apply(frame("dock-x", "新状态", "", 5L, 0L));
        // 乱序到达的旧移除帧（rev=4）不能把 rev=5 的槽删掉
        RemoteSlotBridge.apply(frame("dock-x", "", "", 4L, 0L));

        assertEquals(1, DockSlotBus.list().size());
        assertEquals("新状态", DockSlotBus.list().get(0).title);
    }

    @Test
    public void ttlIsConvertedToAbsoluteExpireAt() throws Exception {
        long before = System.currentTimeMillis();
        RemoteSlotBridge.apply(frame("dock-ttl", "T", "x", 1L, 5000L));

        DockSlotBus.Slot slot = DockSlotBus.list().get(0);
        assertEquals(1L, slot.rev);
        assertEquals("plugin", slot.source);
        assertTrue("expireAt 应约等于收到时刻 + ttl", slot.expireAt >= before + 4000L);
        assertEquals(0, DockSlotBus.sweepExpired(before - 1L));
        assertEquals(1, DockSlotBus.sweepExpired(System.currentTimeMillis() + 6000L));
        assertTrue(DockSlotBus.list().isEmpty());
    }

    @Test
    public void legacyFrameWithoutRevStillApplies() throws Exception {
        RemoteSlotBridge.apply(frame("dock-legacy", "v5", "", 9L, 0L));

        // 旧插件（rev 字段整个没有）必须照旧覆盖，不能被 rev 水位误杀
        JSONObject legacy = new JSONObject();
        legacy.put("id", "dock-legacy");
        legacy.put("title", "旧插件帧");
        legacy.put("detail", "");
        RemoteSlotBridge.apply(legacy);

        assertEquals("旧插件帧", DockSlotBus.list().get(0).title);
    }

    @Test
    public void explicitRemoveWithRevIsHonored() throws Exception {
        RemoteSlotBridge.apply(frame("dock-r", "在", "", 2L, 0L));

        JSONObject remove = new JSONObject();
        remove.put("id", "dock-r");
        remove.put("remove", true);
        remove.put("rev", 3L);
        RemoteSlotBridge.apply(remove);

        assertTrue(DockSlotBus.list().isEmpty());
    }

    // ===== §20 修订：插件重载（epoch 变大，rev 从 1 重来）=====

    @Test
    public void pluginReloadEpochAllowsRevReset() throws Exception {
        RemoteSlotBridge.apply(withEpoch(frame("dock-e", "旧世代", "", 99L, 0L), 1000L));

        // 插件重载：新世代 rev=1（比旧水位 99 小），必须能覆盖
        RemoteSlotBridge.apply(withEpoch(frame("dock-e", "新世代", "", 1L, 0L), 2000L));

        assertEquals(1, DockSlotBus.list().size());
        assertEquals("新世代", DockSlotBus.list().get(0).title);
        assertEquals(1L, DockSlotBus.list().get(0).rev);
    }

    @Test
    public void oldEpochFramesAreDropped() throws Exception {
        RemoteSlotBridge.apply(withEpoch(frame("dock-e", "新世代", "", 5L, 0L), 2000L));

        // 上一世代迟到的帧（epoch 更小）不能盖回/复活
        RemoteSlotBridge.apply(withEpoch(frame("dock-e", "上一世代", "", 88L, 0L), 1000L));

        assertEquals("新世代", DockSlotBus.list().get(0).title);
    }

    @Test
    public void epochChangeClearsPreviousGenerationPluginSlots() throws Exception {
        RemoteSlotBridge.apply(withEpoch(frame("dock-old", "上一世代残留", "", 1L, 0L), 1000L));
        RemoteSlotBridge.apply(withEpoch(frame("dock-new", "新世代", "", 1L, 0L), 2000L));

        assertEquals(1, DockSlotBus.list().size());
        assertEquals("新世代", DockSlotBus.list().get(0).title);
    }

    @Test
    public void removalWorksAfterPluginReload() throws Exception {
        RemoteSlotBridge.apply(withEpoch(frame("dock-rm", "旧槽", "", 7L, 0L), 1000L));

        // 插件重载后 phone_ui 发空帧移除：epoch 变大 + rev=1，必须真的删掉
        RemoteSlotBridge.apply(withEpoch(frame("dock-rm", "", "", 1L, 0L), 2000L));

        assertTrue(DockSlotBus.list().isEmpty());
    }

    // ===== §22（2026-11-15）：level 档位 + 批量 apply（快照） =====

    private static JSONObject levelFrame(String id, String level) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", id);
        o.put("detail", "");
        o.put("level", level);
        return o;
    }

    @Test
    public void levelOnlyFrameUsesTierTable() throws Exception {
        RemoteSlotBridge.apply(levelFrame("dock-p2", "p2"));
        assertEquals("只给 level 也要排对", SlotPriorities.P2, DockSlotBus.list().get(0).priority);
    }

    @Test
    public void levelWinsOverPriorityWhenBothGiven() throws Exception {
        JSONObject o = levelFrame("dock-p0", "p0");
        o.put("priority", 50);
        RemoteSlotBridge.apply(o);
        assertEquals("level 优先（p0 不能因为旧 priority=50 被排到最后）",
                SlotPriorities.P0, DockSlotBus.list().get(0).priority);
    }

    @Test
    public void unknownLevelFallsBackToP4WithoutPriority() throws Exception {
        RemoteSlotBridge.apply(levelFrame("dock-unknown", "p9"));
        assertEquals(SlotPriorities.DEFAULT, DockSlotBus.list().get(0).priority);
    }

    @Test
    public void unknownLevelKeepsLegacyPriority() throws Exception {
        JSONObject o = levelFrame("dock-legacy-pri", "p9");
        o.put("priority", 55);
        RemoteSlotBridge.apply(o);
        assertEquals("未知档位但有旧数值 → 按数值排（向后兼容）",
                55, DockSlotBus.list().get(0).priority);
    }

    @Test
    public void neitherLevelNorPriorityDefaultsToP4() throws Exception {
        RemoteSlotBridge.apply(frame("dock-default", "无档位", "", 1L, 0L));
        assertEquals(SlotPriorities.DEFAULT, DockSlotBus.list().get(0).priority);
    }

    @Test
    public void batchApplyAppliesEveryFrameInOrder() throws Exception {
        JSONArray batch = new JSONArray();
        batch.put(withEpoch(frame("dock-batch", "第一版", "", 1L, 0L), 1000L));
        batch.put(withEpoch(frame("dock-batch", "第二版", "", 2L, 0L), 1000L));
        batch.put(withEpoch(frame("dock-batch", "第三版", "", 3L, 0L), 1000L));

        assertEquals(3, RemoteSlotBridge.applyAll(batch));
        assertEquals(1, DockSlotBus.list().size());
        assertEquals("第三版", DockSlotBus.list().get(0).title);
        assertEquals(3L, DockSlotBus.list().get(0).rev);
    }

    @Test
    public void batchApplyHonorsRevOrderAndRemove() throws Exception {
        JSONArray batch = new JSONArray();
        batch.put(frame("dock-b", "新", "", 5L, 0L));
        batch.put(frame("dock-b", "迟到的旧帧", "", 4L, 0L));
        batch.put(frame("dock-c", "待移除", "", 1L, 0L));

        assertEquals(3, RemoteSlotBridge.applyAll(batch));
        assertEquals("回退 rev 在批量里同样丢弃", "新", DockSlotBus.list().get(0).title);

        JSONArray removals = new JSONArray();
        JSONObject remove = new JSONObject();
        remove.put("id", "dock-b");
        remove.put("remove", true);
        remove.put("rev", 6L);
        removals.put(remove);
        assertEquals(1, RemoteSlotBridge.applyAll(removals));
        assertEquals(1, DockSlotBus.list().size());
        assertEquals("dock-c", DockSlotBus.list().get(0).id);
    }

    @Test
    public void batchApplyHandlesEpochChangeInsideBatch() throws Exception {
        JSONArray batch = new JSONArray();
        batch.put(withEpoch(frame("dock-gen", "旧世代", "", 99L, 0L), 1000L));
        batch.put(withEpoch(frame("dock-gen", "新世代", "", 1L, 0L), 2000L));

        assertEquals(2, RemoteSlotBridge.applyAll(batch));
        assertEquals(1, DockSlotBus.list().size());
        assertEquals("新世代", DockSlotBus.list().get(0).title);
        assertEquals(1L, DockSlotBus.list().get(0).rev);
    }

    @Test
    public void batchApplySkipsInvalidFrames() throws Exception {
        JSONArray batch = new JSONArray();
        batch.put(frame("dock-ok", "有效", "", 1L, 0L));
        JSONObject noId = new JSONObject();
        noId.put("title", "没有 id");
        batch.put(noId);
        batch.put(JSONObject.NULL);

        assertEquals("坏帧/无 id 帧跳过", 1, RemoteSlotBridge.applyAll(batch));
        assertEquals(1, DockSlotBus.list().size());
        assertEquals(0, RemoteSlotBridge.applyAll(null));
    }

    @Test
    public void batchTtlIsRelativeToArrival() throws Exception {
        JSONArray batch = new JSONArray();
        batch.put(frame("dock-ttl-batch", "快照来的", "", 1L, 4000L));
        long before = System.currentTimeMillis();
        assertEquals(1, RemoteSlotBridge.applyAll(batch));

        DockSlotBus.Slot slot = DockSlotBus.list().get(0);
        assertTrue("ttl_ms 仍按到达时刻换算 expireAt", slot.expireAt >= before + 3000L);
    }
}
