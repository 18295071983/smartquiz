package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * 审批放行登记表的作用域语义。
 *
 * <p>真机上出现过"一次查地图被弹 6 次"，用户最后点了最宽的那档却不知道它覆盖整个会话。
 * 现在每一档的范围都是显式的 key（这一次 / 本轮该服务 / 本会话该服务 / 本会话所有服务），
 * 这张表测的就是"范围没有互相漏"：漏一次就意味着下一次该问的没问。</p>
 */
public class McpApprovalScopeTest {

    /** 登记表是进程级静态：不逐条清场，上一条测试会让下一条"莫名被放行"。 */
    @Before
    public void clearRegistry() {
        McpApprovalManager.clearAllForTest();
    }

    @Test
    public void allowOnceRegistersNothing() {
        McpApprovalManager.record(McpApprovalManager.Decision.ALLOW_ONCE, "s1", "r1", "amap");
        assertFalse(McpApprovalManager.isAllowed("s1", "r1", "amap"));
    }

    @Test
    public void runScopeExpiresWithTheRunAndDoesNotLeakIntoNextRun() {
        McpApprovalManager.record(McpApprovalManager.Decision.ALLOW_RUN, "s1", "r1", "amap");
        assertTrue(McpApprovalManager.isAllowed("s1", "r1", "amap"));
        assertFalse("换一轮就该重新问", McpApprovalManager.isAllowed("s1", "r2", "amap"));
        assertFalse("本轮档绑在会话上：别的会话即使 run id 撞了也不认",
                McpApprovalManager.isAllowed("s9", "r1", "amap"));
        McpApprovalManager.clearRun("s1", "r1");
        assertFalse(McpApprovalManager.isAllowed("s1", "r1", "amap"));
    }

    @Test
    public void sessionScopeIsPerServer() {
        McpApprovalManager.record(McpApprovalManager.Decision.ALLOW_SESSION, "s2", "rA", "amap");
        assertTrue(McpApprovalManager.isAllowed("s2", "rA", "amap"));
        assertTrue("本轮之后同会话仍有效", McpApprovalManager.isAllowed("s2", "rB", "amap"));
        assertFalse("别的服务不在范围内", McpApprovalManager.isAllowed("s2", "rB", "other"));
        assertFalse("别的会话不在范围内", McpApprovalManager.isAllowed("s3", "rA", "amap"));
    }

    @Test
    public void sessionAllScopeCoversEveryServerButNotOtherSessions() {
        McpApprovalManager.record(McpApprovalManager.Decision.ALLOW_SESSION_ALL, "s4", "r1", "amap");
        assertTrue(McpApprovalManager.isAllowed("s4", "rX", "amap"));
        assertTrue(McpApprovalManager.isAllowed("s4", "rX", "tencent-map"));
        assertFalse(McpApprovalManager.isAllowed("s5", "rX", "tencent-map"));
    }

    @Test
    public void alwaysDecisionIsNotStoredHere() {
        // 这一档由调用方写进配置（持久），内存表不该替它记一份，否则"取消配置"后还残留放行
        McpApprovalManager.record(McpApprovalManager.Decision.ALLOW_ALWAYS, "s6", "r1", "amap");
        assertFalse(McpApprovalManager.isAllowed("s6", "r1", "amap"));
    }

    @Test
    public void emptySessionAndRunNeverMatch() {
        assertFalse(McpApprovalManager.isAllowed("", "", "amap"));
        assertFalse(McpApprovalManager.isAllowed(null, null, "amap"));
        McpApprovalManager.record(McpApprovalManager.Decision.ALLOW_SESSION, "", "r", "amap");
        McpApprovalManager.record(McpApprovalManager.Decision.ALLOW_RUN, "s", "", "amap");
        assertFalse(McpApprovalManager.isAllowed("", "", "amap"));
    }

    @Test
    public void clearingSessionDropsOnlyThatSession() {
        McpApprovalManager.record(McpApprovalManager.Decision.ALLOW_SESSION, "s7", "r1", "amap");
        McpApprovalManager.record(McpApprovalManager.Decision.ALLOW_SESSION, "s8", "r1", "amap");
        McpApprovalManager.clearSession("s7");
        assertFalse(McpApprovalManager.isAllowed("s7", "r1", "amap"));
        assertTrue("别的会话不受影响", McpApprovalManager.isAllowed("s8", "r1", "amap"));
        McpApprovalManager.clearSession("");
        McpApprovalManager.clearSession(null);
        assertTrue(McpApprovalManager.isAllowed("s8", "r1", "amap"));
    }

    @Test
    public void registryIsBoundedSoALongLivedProcessCannotGrowForever() {
        for (int i = 0; i < 400; i++) {
            McpApprovalManager.record(McpApprovalManager.Decision.ALLOW_SESSION, "bulk-" + i, "r", "srv");
        }
        assertTrue("上限必须存在，否则会话只增不减就是泄漏：" + McpApprovalManager.allowCount(),
                McpApprovalManager.allowCount() <= 129);
    }

    @Test
    public void decisionsCoverSixScopes() {
        // 少一档就没有可用的粒度，多一档（永久）就只剩管理页能给——弹框里不该出现永久档
        assertEquals(6, McpApprovalManager.Decision.values().length);
        assertNotNull(McpApprovalManager.Decision.valueOf("ALLOW_SESSION_ALL"));
        assertNotNull(McpApprovalManager.Decision.valueOf("ALLOW_RUN"));
    }
}
