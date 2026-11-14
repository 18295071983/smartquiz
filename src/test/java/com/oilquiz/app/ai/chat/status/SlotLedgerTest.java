package com.oilquiz.app.ai.chat.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

/**
 * 插槽账本的契约（§20，2026-11-15）：rev 乱序保护 / TTL / 单一写者兜底。
 *
 * <p>纯 JVM，时间全部由参数传入，所以能精确构造"3 秒窗口""TTL 差 1ms"这些边界。</p>
 */
public class SlotLedgerTest {

    private static DockSlotBus.Slot slot(String id, String title) {
        return slot(id, title, 0L, 0L, "plugin");
    }

    private static DockSlotBus.Slot slot(String id, String title, long rev, long expireAt, String source) {
        return new DockSlotBus.Slot(id, 40, title, "", "", false,
                "display", "state", false, 0, rev, expireAt, source);
    }

    @Test
    public void revIncreasesAndOverwrites() {
        SlotLedger ledger = new SlotLedger();
        assertEquals(SlotLedger.Result.APPLIED, ledger.update(slot("job", "A", 1L, 0L, "plugin"), 1000L));
        assertEquals(SlotLedger.Result.APPLIED, ledger.update(slot("job", "B", 2L, 0L, "plugin"), 1001L));
        assertEquals(1, ledger.size());
        assertEquals("B", ledger.get("job").title);
        assertEquals(2L, ledger.lastRev("job"));
    }

    @Test
    public void staleRevIsDropped() {
        SlotLedger ledger = new SlotLedger();
        ledger.update(slot("job", "新", 5L, 0L, "plugin"), 1000L);
        assertEquals(SlotLedger.Result.STALE_REV,
                ledger.update(slot("job", "旧", 4L, 0L, "plugin"), 1001L));
        assertEquals("迟到的旧 rev 不能盖回去", "新", ledger.get("job").title);
    }

    @Test
    public void sameRevIsDuplicateAndDropped() {
        SlotLedger ledger = new SlotLedger();
        ledger.update(slot("job", "A", 7L, 0L, "plugin"), 1000L);
        assertEquals(SlotLedger.Result.DUPLICATE_REV,
                ledger.update(slot("job", "A2", 7L, 0L, "plugin"), 1001L));
        assertEquals("A", ledger.get("job").title);
    }

    @Test
    public void legacyRevZeroStillOverwrites() {
        SlotLedger ledger = new SlotLedger();
        ledger.update(slot("job", "A", 0L, 0L, ""), 1000L);
        assertEquals(SlotLedger.Result.APPLIED, ledger.update(slot("job", "B", 0L, 0L, ""), 1001L));
        assertEquals("旧插件/本地写入保持覆盖语义", "B", ledger.get("job").title);
    }

    @Test
    public void legacyRevZeroStillAppliesAfterVersionedFrames() {
        SlotLedger ledger = new SlotLedger();
        ledger.update(slot("job", "插件 v5", 5L, 0L, "plugin"), 1000L);
        // 旧插件 / 不带 rev 的推送不能被"已存 rev"误杀：只有有版本帧才参与乱序校验
        assertEquals(SlotLedger.Result.APPLIED,
                ledger.update(slot("job", "无版本帧", 0L, 0L, ""), 1001L));
        assertEquals("无版本帧", ledger.get("job").title);
        assertEquals("rev=0 不推进水位", 5L, ledger.lastRev("job"));
    }

    @Test
    public void removeWithNewerRevWinsAndOldFrameCannotResurrect() {
        SlotLedger ledger = new SlotLedger();
        ledger.update(slot("job", "跑着", 3L, 0L, "plugin"), 1000L);
        assertEquals(SlotLedger.Result.APPLIED, ledger.remove("job", 4L));
        assertNull(ledger.get("job"));
        assertEquals(SlotLedger.Result.STALE_REV,
                ledger.update(slot("job", "复活", 3L, 0L, "plugin"), 1001L));
        assertNull("旧帧不能把已移除的槽位复活", ledger.get("job"));
    }

    @Test
    public void sameRevReplayIsAcceptedAfterClear() {
        SlotLedger ledger = new SlotLedger();
        ledger.update(slot("jobs", "2 个在跑", 8L, 0L, "plugin"), 1000L);
        ledger.clearNonPinned();
        assertNull(ledger.get("jobs"));
        // 断连清空后插件按原 rev 补发同一条：应恢复显示（否则重连永远丢槽）
        assertEquals(SlotLedger.Result.APPLIED,
                ledger.update(slot("jobs", "2 个在跑", 8L, 0L, "plugin"), 2000L));
        assertNotNull(ledger.get("jobs"));
    }

    @Test
    public void pluginRestartRevOneIsAcceptedAfterClear() {
        SlotLedger ledger = new SlotLedger();
        ledger.update(slot("jobs", "旧世代 v9", 9L, 0L, "plugin"), 1000L);
        ledger.clearNonPinned();
        // 插件进程重启 → SLOT_REV 归零 → 新世代从 rev=1 开始；不能把它当回退帧永久丢弃
        assertEquals(SlotLedger.Result.APPLIED,
                ledger.update(slot("jobs", "新世代 v1", 1L, 0L, "plugin"), 2000L));
        assertEquals("新世代 v1", ledger.get("jobs").title);
    }

    @Test
    public void clearKeepsPinnedOnly() {
        SlotLedger ledger = new SlotLedger();
        ledger.update(new DockSlotBus.Slot("pinned", 6, "通道落后", "", "connect", false,
                "display", "state", true, 0), 1000L);
        ledger.update(slot("job", "任务"), 1000L);
        assertEquals(1, ledger.clearNonPinned());
        assertEquals(1, ledger.size());
        assertNotNull(ledger.get("pinned"));
        assertNull(ledger.get("job"));
    }

    @Test
    public void ttlExpiresOnlyAfterDeadline() {
        SlotLedger ledger = new SlotLedger();
        ledger.update(new DockSlotBus.Slot("compaction", 10, "压缩中", "", "", true,
                "display", "state", false, 0, 1L, 5000L, "plugin"), 1000L);
        assertEquals(0, ledger.sweep(4999L).size());
        assertNotNull(ledger.get("compaction"));
        List<String> gone = ledger.sweep(5001L);
        assertEquals(1, gone.size());
        assertEquals("compaction", gone.get(0));
        assertNull(ledger.get("compaction"));
    }

    @Test
    public void noTtlIsNeverSwept() {
        SlotLedger ledger = new SlotLedger();
        ledger.update(slot("goal", "目标", 1L, 0L, "plugin"), 1000L);
        assertEquals(0, ledger.sweep(Long.MAX_VALUE - 1).size());
        assertNotNull("稳定态靠显式 remove，不因时间消失", ledger.get("goal"));
    }

    @Test
    public void frameAlreadyExpiredOnArrivalIsDropped() {
        SlotLedger ledger = new SlotLedger();
        assertEquals(SlotLedger.Result.EXPIRED,
                ledger.update(new DockSlotBus.Slot("retry", 15, "重试中", "", "", true,
                        "display", "alert", false, 0, 1L, 900L, "plugin"), 1000L));
        assertEquals(0, ledger.size());
    }

    @Test
    public void localFallbackYieldsWithinThreeSecondsOfPluginWrite() {
        SlotLedger ledger = new SlotLedger();
        ledger.update(slot("compaction", "插件压缩", 2L, 0L, "plugin"), 1000L);
        assertEquals(SlotLedger.Result.LOCAL_SUPPRESSED,
                ledger.updateLocal(slot("compaction", "本地兜底", 0L, 0L, ""), 3999L));
        assertEquals("插件写过就不许本地再出一行", "插件压缩", ledger.get("compaction").title);
        assertEquals(SlotLedger.Result.APPLIED,
                ledger.updateLocal(slot("compaction", "本地兜底", 0L, 0L, ""), 4000L));
        assertEquals("插件失联 3s 后兜底生效", "本地兜底", ledger.get("compaction").title);
        assertEquals("local", ledger.get("compaction").source);
    }

    @Test
    public void localFallbackWithoutPluginStillWorks() {
        SlotLedger ledger = new SlotLedger();
        assertEquals(SlotLedger.Result.APPLIED,
                ledger.updateLocal(slot("compaction", "本地兜底", 0L, 0L, ""), 1000L));
        assertEquals("旧插件不推时能力不能删", "本地兜底", ledger.get("compaction").title);
        assertEquals("local", ledger.get("compaction").source);
    }

    @Test
    public void pluginRevWatermarkSurvivesLocalFallback() {
        SlotLedger ledger = new SlotLedger();
        ledger.update(slot("job", "插件 v5", 5L, 0L, "plugin"), 1000L);
        ledger.updateLocal(slot("job", "本地", 0L, 0L, ""), 5000L);
        // 插件恢复后 rev=6 必须能写回（本地写入不推进也不该挡住新版本）
        assertEquals(SlotLedger.Result.APPLIED,
                ledger.update(slot("job", "插件 v6", 6L, 0L, "plugin"), 5001L));
        assertEquals("插件 v6", ledger.get("job").title);
        assertEquals(6L, ledger.lastRev("job"));
    }

    @Test
    public void epochChangeClearsOldPluginSlotsAndResetsWatermarks() {
        SlotLedger ledger = new SlotLedger();
        ledger.update(slot("job", "旧世代 v9", 9L, 0L, "plugin"), 1000L);
        ledger.updateLocal(slot("remote/attention", "本地状态", 0L, 0L, ""), 1000L);

        // 插件重载：epoch 变大 → 旧插件槽清掉、rev 水位归零；本地派生的槽要保留
        assertEquals(1, ledger.applyEpoch(2000L));
        assertNull(ledger.get("job"));
        assertNotNull("本地槽不能被插件世代切换清掉", ledger.get("remote/attention"));

        // 新世代 rev 从 1 开始也必须能写入（重载后更新/移除不再永久失效）
        assertEquals(SlotLedger.Result.APPLIED,
                ledger.update(slot("job", "新世代 v1", 1L, 0L, "plugin"), 2001L));
        assertEquals("新世代 v1", ledger.get("job").title);
    }

    @Test
    public void olderEpochFramesAreRejected() {
        SlotLedger ledger = new SlotLedger();
        ledger.applyEpoch(2000L);
        assertEquals(-1, ledger.applyEpoch(1999L));
        assertEquals(2000L, ledger.pluginEpoch());
    }

    @Test
    public void sameEpochIsNoOpAndKeepsSlots() {
        SlotLedger ledger = new SlotLedger();
        ledger.applyEpoch(2000L);
        ledger.update(slot("job", "v1", 1L, 0L, "plugin"), 1000L);
        assertEquals(0, ledger.applyEpoch(2000L));
        assertNotNull(ledger.get("job"));
    }
}
