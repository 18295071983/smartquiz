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
}
