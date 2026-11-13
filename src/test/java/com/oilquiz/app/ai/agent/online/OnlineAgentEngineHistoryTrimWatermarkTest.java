package com.oilquiz.app.ai.agent.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 历史裁剪水位（HYSTERESIS，2026-11-13）的纯算术契约。
 *
 * <p>为什么单独立一组用例：这两个判据决定"这一轮要不要动历史"。动一次历史，服务商侧
 * 前缀缓存就从第一个被移除的 token 起失效（整段重新 prefill）；不动则前缀继续命中。
 * 判据写错的表现不是崩溃，而是"长对话越聊越慢、命中率莫名掉到 0"，从日志上极难归因，
 * 所以把边界（100%、恰好 110%、110% 多一点）和大窗口溢出都钉死在测试里。
 *
 * <p>只测纯函数，不构造引擎实例：{@link OnlineAgentEngine} 的实例侧依赖 Android 的
 * Handler/Context，而这两个判据不碰任何状态。
 */
public class OnlineAgentEngineHistoryTrimWatermarkTest {

    // ===== 高水位：严格大于 110% 才裁 =====

    @Test
    public void belowBudgetNeverTrims() {
        assertFalse(OnlineAgentEngine.overHistoryHighWater(500, 1000));
        assertFalse(OnlineAgentEngine.overHistoryHighWater(1000, 1000)); // 恰好等于预算
    }

    @Test
    public void insideHysteresisBandDoesNotTrim() {
        assertFalse(OnlineAgentEngine.overHistoryHighWater(1001, 1000)); // 100.1%
        assertFalse(OnlineAgentEngine.overHistoryHighWater(1099, 1000)); // 109.9%
        assertFalse(OnlineAgentEngine.overHistoryHighWater(1100, 1000)); // 恰好高水位
    }

    @Test
    public void aboveHighWaterTrims() {
        assertTrue(OnlineAgentEngine.overHistoryHighWater(1101, 1000)); // 110.1%
        assertTrue(OnlineAgentEngine.overHistoryHighWater(1500, 1000));
        assertTrue(OnlineAgentEngine.overHistoryHighWater(9000, 1000));
    }

    // ===== 低水位（裁剪目标）=====

    @Test
    public void trimTargetIsEightyPercentOfBudget() {
        assertEquals(800, OnlineAgentEngine.historyTrimTarget(1000));
        assertEquals(48000, OnlineAgentEngine.historyTrimTarget(60000));
        // 预算下限 8192（极小上下文）：8192 × 80% = 6553.6 → 6553
        assertEquals(6553, OnlineAgentEngine.historyTrimTarget(8192));
    }

    @Test
    public void trimTargetStaysBelowHighWater() {
        for (int budget : new int[]{8192, 24000, 60000, 600000}) {
            int target = OnlineAgentEngine.historyTrimTarget(budget);
            assertTrue("裁到 " + target + " 后必须低于高水位，否则下一次请求立刻重裁",
                    !OnlineAgentEngine.overHistoryHighWater(target, budget));
            assertTrue("裁剪目标应小于预算本身", target < budget);
        }
    }

    // ===== 边界：窗口未知 / 溢出 =====

    @Test
    public void unknownBudgetNeverTriggers() {
        assertFalse(OnlineAgentEngine.overHistoryHighWater(1_000_000, 0));
        assertFalse(OnlineAgentEngine.overHistoryHighWater(1_000_000, -1));
        assertEquals(0, OnlineAgentEngine.historyTrimTarget(0));
        assertEquals(0, OnlineAgentEngine.historyTrimTarget(-1));
    }

    @Test
    public void largeWindowDoesNotOverflow() {
        int budget = 600_000; // 1M 窗口 × 60%
        assertFalse(OnlineAgentEngine.overHistoryHighWater(659_999, budget)); // 109.99%
        assertTrue(OnlineAgentEngine.overHistoryHighWater(660_001, budget));  // 110.01%
        assertEquals(480_000, OnlineAgentEngine.historyTrimTarget(budget));
    }
}
