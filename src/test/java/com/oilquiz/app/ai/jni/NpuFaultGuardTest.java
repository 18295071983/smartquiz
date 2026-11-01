package com.oilquiz.app.ai.jni;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * NPU 故障守卫的决策核心（纯函数，不依赖 Android）。
 *
 * <p>守卫要保证的四件事：<br>
 * ① 单次硬件层中断不降级（偶发不算坏），连续两次才降；<br>
 * ② 降级只影响 hexagon —— 用户显式选的 opencl/vulkan/auto 一律不动；<br>
 * ③ 一次正常收尾就清零并解除降级（自动交还，不需要人记着改回来）；<br>
 * ④ 降级期间的后续中断不会把状态弄丢（保持 armed，不重复计数到负数或回退）。</p>
 */
public class NpuFaultGuardTest {

    @Test
    public void singleFatalDoesNotDemoteButTwoConsecutiveDo() {
        NpuFaultGuard.Snapshot s1 = NpuFaultGuard.onFatalObserved(NpuFaultGuard.idle());
        assertEquals("一次中断只累加计数", 1, s1.consecutiveFatal);
        assertFalse("偶发一次不该降级", s1.demoteArmed);

        NpuFaultGuard.Snapshot s2 = NpuFaultGuard.onFatalObserved(s1);
        assertEquals(2, s2.consecutiveFatal);
        assertTrue("连续两次必须降级", s2.demoteArmed);
    }

    @Test
    public void demoteOnlyRewritesHexagon() {
        NpuFaultGuard.Snapshot armed = new NpuFaultGuard.Snapshot(2, true);
        assertEquals(NpuFaultGuard.FALLBACK_BACKEND,
                NpuFaultGuard.effectiveBackend(armed, "hexagon"));
        assertEquals("用户显式选 opencl 时不该被改",
                "opencl", NpuFaultGuard.effectiveBackend(armed, "opencl"));
        assertEquals("vulkan 不是我们兜的问题，原样保留",
                "vulkan", NpuFaultGuard.effectiveBackend(armed, "vulkan"));
        assertEquals("auto 同样不掺和", "auto", NpuFaultGuard.effectiveBackend(armed, "auto"));
        assertEquals(null, NpuFaultGuard.effectiveBackend(armed, null));
    }

    @Test
    public void notArmedNeverRewrites() {
        NpuFaultGuard.Snapshot s = new NpuFaultGuard.Snapshot(1, false);
        assertEquals("hexagon", NpuFaultGuard.effectiveBackend(s, "hexagon"));
    }

    @Test
    public void oneHealthyRoundClearsCounterAndDemote() {
        NpuFaultGuard.Snapshot armed = new NpuFaultGuard.Snapshot(3, true);
        NpuFaultGuard.Snapshot after = NpuFaultGuard.onHealthyRound(armed);
        assertEquals("正常收尾要清零", 0, after.consecutiveFatal);
        assertFalse("正常收尾要自动解除降级（下次装载回到偏好后端）", after.demoteArmed);
        assertEquals("解除后 hexagon 原样下发", "hexagon",
                NpuFaultGuard.effectiveBackend(after, "hexagon"));
    }

    @Test
    public void fatalWhileArmedStaysArmed() {
        NpuFaultGuard.Snapshot armed = new NpuFaultGuard.Snapshot(2, true);
        NpuFaultGuard.Snapshot after = NpuFaultGuard.onFatalObserved(armed);
        assertEquals(3, after.consecutiveFatal);
        assertTrue("已降级状态下再中断不能把标记抹掉", after.demoteArmed);
    }

    @Test
    public void snapshotSanitisesNegativeCounts() {
        NpuFaultGuard.Snapshot s = new NpuFaultGuard.Snapshot(-5, false);
        assertEquals("外部传进来的脏值不能变成负数计数", 0, s.consecutiveFatal);
    }
}
