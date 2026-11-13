package com.oilquiz.app.ai.agent.software.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 本地历史裁剪的双水位契约（HYSTERESIS，2026-11-13）。
 *
 * <p>为什么单独立一组：本地裁剪的触发点直接决定"这一轮会不会打掉 KV 前缀"。旧实现
 * "超出就裁、裁到刚好不超"，下一轮立刻又超 —— 每轮平移一次窗口、每轮全量 re-prefill，
 * 真机表现是越聊越慢（20s/轮）。这里钉三件事：</p>
 * <ol>
 *   <li>高水位**不得低于**预算（否则比旧行为更早裁）；</li>
 *   <li>高水位**不得贴到** native 门（{@code door - 512} 以下），大窗口下尤其要防；</li>
 *   <li>裁剪目标恒为预算的 80%，且低于高水位（否则裁完立刻重裁）。</li>
 * </ol>
 */
public class LocalHistoryTrimWatermarkTest {

    /** 典型 8K 窗口：门 = n_ctx − 512 = 7680，预算 = 门 − 4000 = 3680 */
    @Test
    public void highWaterEatsOnlyQuarterOfReserve() {
        int budget = 3680;
        int door = 7680;
        int high = AgentLoopEngine.historyHighWater(budget, door);

        assertEquals("吃 1/4 余量：3680 + (7680-3680)/4", 4680, high);
        assertTrue("不能低于预算", high >= budget);
        assertTrue("必须留在门以下", high < door - 512 + 1);
    }

    /** 大窗口最容易踩坑：按 110% 算会直接越过门 */
    @Test
    public void highWaterNeverCrossesDoorOnLargeContexts() {
        int budget = 61024;   // 64K 窗口：门 65024 − 4000
        int door = 65024;
        int high = AgentLoopEngine.historyHighWater(budget, door);

        assertTrue("高水位必须严格在拒收线之下", high < door - 512);
        assertTrue("且不低于预算", high >= budget);
        assertTrue("不按 110% 放大", high < budget * 11 / 10);
    }

    /** 余量很小（预算已经贴着门）时，也不能被夹到预算以下 */
    @Test
    public void tinyReserveFallsBackToBudget() {
        int budget = 1000;
        int door = 1480;      // 余量 480，1/4=120；ceiling=968 < 1000 → 夹回预算
        assertEquals(1000, AgentLoopEngine.historyHighWater(budget, door));
    }

    @Test
    public void unknownDoorKeepsLegacyThreshold() {
        assertEquals(5000, AgentLoopEngine.historyHighWater(5000, 0));
        assertEquals(0, AgentLoopEngine.historyHighWater(0, 8000));
    }

    @Test
    public void trimTargetIsEightyPercentWithFloor() {
        assertEquals(3680 * 80 / 100, AgentLoopEngine.historyTrimTarget(3680));
        assertEquals("极小窗口不低于 256", 256, AgentLoopEngine.historyTrimTarget(96));
        assertEquals(0, AgentLoopEngine.historyTrimTarget(0));
    }

    @Test
    public void targetStaysBelowHighWater() {
        for (int[] pair : new int[][]{{3680, 7680}, {61024, 65024}, {4192, 9216}, {96, 4608}}) {
            int high = AgentLoopEngine.historyHighWater(pair[0], pair[1]);
            int target = AgentLoopEngine.historyTrimTarget(pair[0]);
            assertTrue("裁到目标后必须低于高水位，否则下一轮立刻重裁", target < high);
        }
    }
}
