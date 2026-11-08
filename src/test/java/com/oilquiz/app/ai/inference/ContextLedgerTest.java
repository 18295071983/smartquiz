package com.oilquiz.app.ai.inference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * INFERENCE-GATEWAY/CONTEXT-LEDGER 基础设施不变量：
 * 单生成闸门、owner、真实/记账 KV 快照，不依赖 native 已加载。
 */
public class ContextLedgerTest {

    @Test
    public void snapshotIsNonNegativeAndKeepsRealAndBookkeepingSeparate() {
        ContextLedger.Snapshot s = ContextLedger.snapshot();
        assertTrue(s.nCtx >= 0);
        assertTrue(s.realUsed >= 0);
        assertTrue(s.remaining >= 0);
        assertTrue(s.bookkeepingUsed >= 0);
        assertEquals(Math.max(0, s.nCtx - s.realUsed), s.remaining);
        assertTrue(s.promptBudget(0) >= 0);
    }

    @Test
    public void gatewayAllowsExactlyOneGeneration() {
        InferenceGateway g = InferenceGateway.get();
        g.forceEnd(null);
        long first = g.begin("test-first");
        assertTrue("第一次 begin 必须成功", first > 0);
        assertTrue(g.isGenerating());
        assertEquals("second begin while busy must fail", -1, g.begin("test-second"));
        g.end(first);
        assertFalse(g.isGenerating());
        assertEquals("", g.activeOwner());

        long third = g.begin("test-third");
        assertTrue(third > 0);
        g.end(third);
        assertFalse(g.isGenerating());
    }

    @Test
    public void staleGenerationIdCannotReleaseNewOwner() {
        InferenceGateway g = InferenceGateway.get();
        g.forceEnd(null);
        long old = g.begin("old");
        g.end(old);
        long current = g.begin("current");
        g.end(old); // 旧 id 不得释放后来者
        assertTrue("旧 generation 不能误释放新 generation", g.isGenerating());
        assertEquals("current", g.activeOwner());
        g.end(current);
    }
}
