package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.status.DockSlotBus;

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
}
