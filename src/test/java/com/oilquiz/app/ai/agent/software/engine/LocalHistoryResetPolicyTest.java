package com.oilquiz.app.ai.agent.software.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * HISTORY-NULL(2026-11-08)：历史清空判据回归。
 *
 * <p>真机事故：调试桥本地注入不带 history，旧代码把 {@code null} 当“历史已清空”，
 * 同 session 第二轮仍重置序列，prompt 每次都 4292 token 全量 prefill（19.5s），
 * 多轮上下文也丢。这里锁定语义：null=未知/保留；空列表=显式清空。</p>
 */
public class LocalHistoryResetPolicyTest {

    @Test
    public void nullHistoryMeansUnknownNotCleared() {
        assertFalse(AgentLoopEngine.isHistoryExplicitlyCleared(null));
        assertNull(AgentLoopEngine.firstHistoryContent(null));
    }

    @Test
    public void emptyOrBlankHistoryMeansExplicitClear() {
        assertTrue(AgentLoopEngine.isHistoryExplicitlyCleared(Collections.emptyList()));
        assertTrue(AgentLoopEngine.isHistoryExplicitlyCleared(Arrays.asList(
                new AgentLoopEngine.HistoryEntry("user", ""),
                new AgentLoopEngine.HistoryEntry("assistant", "   "))));
        assertNull(AgentLoopEngine.firstHistoryContent(Collections.emptyList()));
    }

    @Test
    public void nonEmptyHistoryKeepsSession() {
        List<AgentLoopEngine.HistoryEntry> history = new ArrayList<>();
        history.add(new AgentLoopEngine.HistoryEntry("user", "1+1"));
        history.add(new AgentLoopEngine.HistoryEntry("assistant", "2"));
        assertFalse(AgentLoopEngine.isHistoryExplicitlyCleared(history));
        assertEquals("1+1", AgentLoopEngine.firstHistoryContent(history));
    }
}
