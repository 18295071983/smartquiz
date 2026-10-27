package com.oilquiz.app.ai.util;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 孤儿历史判定（清扫前的纯函数部分）。
 *
 * <p>钉住三条：①必须从文件名里抠出会话 uuid（含 .bak/.tmp/.round 副产物）；
 * ②没有 uuid 的"默认会话"历史永远不算孤儿（那是无会话时的历史，删会话不该波及）；
 * ③索引为空时一律不判定——否则一次读取失败就会把用户全部历史当垃圾删掉。</p>
 */
public class HistoryOrphanSweepTest {

    private static final String LIVE = "ded5de68-f6c8-4e98-aca6-148a0c169fbe";
    private static final String DEAD = "faf97d02-ea4b-4336-84f7-f4fb70200fa7";

    private static Set<String> known(String... ids) {
        Set<String> s = new HashSet<>();
        Collections.addAll(s, ids);
        return s;
    }

    @Test
    public void extractsSessionIdFromAllSidecarShapes() {
        assertEquals(LIVE, ChatHistoryManager.sessionIdOfHistoryFile(
                "online_agent_history_" + LIVE + "_d82b161a-589b-4662-9720-a5e8fdc4f885.json"));
        assertEquals(LIVE, ChatHistoryManager.sessionIdOfHistoryFile(
                "online_agent_history_" + LIVE + "_m1.json.bak"));
        assertEquals(LIVE, ChatHistoryManager.sessionIdOfHistoryFile(
                "online_agent_history_" + LIVE + "_m1.json.round"));
        assertEquals(LIVE, ChatHistoryManager.sessionIdOfHistoryFile(
                "local_chat_history_" + LIVE + "_default.json"));
        // 默认会话（无 uuid）与无关文件
        assertNull(ChatHistoryManager.sessionIdOfHistoryFile("online_agent_history_m1.json"));
        assertNull(ChatHistoryManager.sessionIdOfHistoryFile("dynamic_tools.json"));
        assertNull(ChatHistoryManager.sessionIdOfHistoryFile(null));
    }

    @Test
    public void orphanOnlyWhenSessionMissingFromIndex() {
        Set<String> live = known(LIVE);
        assertFalse(ChatHistoryManager.isOrphanHistoryFile(
                "online_agent_history_" + LIVE + "_m.json", live));
        assertTrue(ChatHistoryManager.isOrphanHistoryFile(
                "online_agent_history_" + DEAD + "_m.json.bak", live));
        assertTrue(ChatHistoryManager.isOrphanHistoryFile(
                "local_chat_history_" + DEAD + "_default.json", live));
        // 默认会话历史不参与判定
        assertFalse(ChatHistoryManager.isOrphanHistoryFile("online_agent_history_m.json", live));
    }

    @Test
    public void emptyIndexNeverClassifiesAnythingAsOrphan() {
        assertFalse(ChatHistoryManager.isOrphanHistoryFile(
                "local_chat_history_" + DEAD + "_default.json", known()));
        assertFalse(ChatHistoryManager.isOrphanHistoryFile(
                "local_chat_history_" + DEAD + "_default.json", null));
    }
}
