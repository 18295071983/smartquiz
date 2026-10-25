package com.oilquiz.app.infra;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link MainThreadWatchdog#isAnrDeath} 归因判据单测（2026-10-25 崩溃计数按死因归因）。
 *
 * <p>这是"上次是不是 ANR 被杀"的纯函数：只有卡死标记的 boot 与上一次启动代次一致、
 * 且标记未过期时才算 ANR。ANR 命中会把模型相关崩溃计数清零（跳过模型自动加载治不了 UI 卡死）。</p>
 */
public class MainThreadWatchdogTest {

    private static final long DAY = 24 * 3600_000L;

    @Test
    public void nullAnrBootIsNotAnr() {
        assertFalse(MainThreadWatchdog.isAnrDeath(null, "boot-1", 1000L, 0L));
    }

    @Test
    public void bootMismatchIsNotAnr() {
        // 标记来自更早的一次启动，不能算到这次头上
        assertFalse(MainThreadWatchdog.isAnrDeath("boot-old", "boot-1", 1000L, 900L));
    }

    @Test
    public void nullPrevBootIsNotAnr() {
        assertFalse(MainThreadWatchdog.isAnrDeath("boot-1", null, 1000L, 900L));
    }

    @Test
    public void freshAnrMarkerSameBootIsAnr() {
        assertTrue(MainThreadWatchdog.isAnrDeath("boot-1", "boot-1", 5_000L, 1_000L));
    }

    @Test
    public void staleAnrMarkerBeyondTtlIsNotAnr() {
        // 超过 24 小时的旧标记：宁可当真崩溃处理，不再豁免
        assertFalse(MainThreadWatchdog.isAnrDeath("boot-1", "boot-1", DAY + 10L, 0L));
    }

    @Test
    public void clockRewindGuardTreatedAsNotFresh() {
        // now 早于记录时刻（计时源回拨等异常）→ 判非 ANR，交回崩溃分支保守处理
        assertFalse(MainThreadWatchdog.isAnrDeath("boot-1", "boot-1", 100L, 5_000L));
    }

    // ==================== 卡死判定（isStalled） ====================

    /** 真机踩过的坑：主线程吊住 6s 时，若度量取"最近一次投递"，差值只有半秒 → 哨兵永远沉默。 */
    @Test
    public void sixSecondBlockIsDetectedFromLastAnsweredProbe() {
        // 心跳每 500ms 投一拍：投到第 12 拍（t=6000ms）时，主线程只应答到第 1 拍（t=0ms）
        assertTrue(MainThreadWatchdog.isStalled(6_000L, 0L, 12L, 1L, 5_000L));
    }

    @Test
    public void healthyTickingIsNeverStalled() {
        // 主线程一直正常应答：answered 跟上 posted → 不报
        assertFalse(MainThreadWatchdog.isStalled(60_000L, 59_800L, 120L, 120L, 5_000L));
        // 只落后一拍且刚应答过（200ms < 阈值）→ 不报
        assertFalse(MainThreadWatchdog.isStalled(5_200L, 5_000L, 11L, 10L, 5_000L));
    }

    @Test
    public void noOutstandingProbeIsNotStalledEvenAfterLongIdle() {
        // 投递与应答齐平＝主线程刚执行过心跳；长时间空闲不等于卡死
        assertFalse(MainThreadWatchdog.isStalled(120_000L, 0L, 10L, 10L, 5_000L));
    }

    @Test
    public void stallNeedsProbeOutstanding() {
        // answered 落在 posted 后面且超过阈值 → 报
        assertTrue(MainThreadWatchdog.isStalled(5_000L, 0L, 10L, 9L, 5_000L));
        // 差 1ms 到阈值 → 不报（阈值边界取 >=）
        assertFalse(MainThreadWatchdog.isStalled(4_999L, 0L, 10L, 9L, 5_000L));
    }

    @Test
    public void clockRewindGuardDoesNotReportStall() {
        assertFalse(MainThreadWatchdog.isStalled(1_000L, 5_000L, 10L, 1L, 5_000L));
    }
}
