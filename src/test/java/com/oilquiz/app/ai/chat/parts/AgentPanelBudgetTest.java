package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * WebView 面板存活上限（2026-11-18）：同屏超限必须退回原生，释放要配对归还额度。
 */
public class AgentPanelBudgetTest {

    @Before
    public void setUp() {
        AgentPanelBudget.resetForTest();
    }

    @After
    public void tearDown() {
        AgentPanelBudget.resetForTest();
    }

    @Test
    public void acquiresUpToCapThenRefuses() {
        for (int i = 0; i < AgentPanelBudget.MAX_LIVE; i++) {
            assertTrue("第 " + (i + 1) + " 个应拿到额度", AgentPanelBudget.acquire());
        }
        assertFalse("超过上限必须拒绝（调用方退回原生手风琴）", AgentPanelBudget.acquire());
        assertEquals(AgentPanelBudget.MAX_LIVE, AgentPanelBudget.live());
    }

    @Test
    public void releaseReturnsSlot() {
        assertTrue(AgentPanelBudget.acquire());
        assertTrue(AgentPanelBudget.acquire());
        assertTrue(AgentPanelBudget.acquire());
        assertFalse(AgentPanelBudget.acquire());

        AgentPanelBudget.release();
        assertTrue("释放一个后应能再拿一个", AgentPanelBudget.acquire());
        assertEquals(AgentPanelBudget.MAX_LIVE, AgentPanelBudget.live());
    }

    @Test
    public void releaseIsClampedAtZero() {
        AgentPanelBudget.release();
        AgentPanelBudget.release();
        assertEquals("多还的额度不能把计数压成负数", 0, AgentPanelBudget.live());
    }
}
