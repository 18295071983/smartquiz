package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 电脑端 `/status` 快照的解析与文案契约（BAR-V2，2026-11-13）。
 *
 * <p>状态栏是用户判断"电脑忙不忙"的唯一入口，这些都只是字符串拼装，写错不会崩，
 * 只会让人误判——所以边界（缺字段、全空闲、审批优先于排队）逐条钉住。</p>
 */
public class RemoteStatusSnapshotTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @Test
    public void parsesNestedStatusFields() {
        Map<String, Object> root = map(
                "ok", true, "version", 8,
                "cli", map("running", 1, "queued", 2, "max", 1),
                "approval_relay", map("pending", 1),
                "devices", 1, "sessions_count", 2, "active_jobs", 3);

        RemoteStatusSnapshot s = RemoteStatusSnapshot.parse(root, 100L);

        assertTrue(s.ok);
        assertEquals("8", s.version);
        assertEquals(1, s.cliRunning);
        assertEquals(2, s.cliQueued);
        assertEquals(1, s.cliMax);
        assertEquals(1, s.approvalsPending);
        assertEquals(2, s.sessionsCount);
        assertEquals(3, s.activeJobs);
        assertEquals(100L, s.fetchedAt);
    }

    @Test
    public void headlineShowsBusySegments() {
        RemoteStatusSnapshot s = new RemoteStatusSnapshot(true, "8", 1, 2, 1, 1, 1, 2, 0, 0);
        assertEquals("运行中 · 审批 1 · 排队 2", s.headline());
    }

    @Test
    public void idleWhenNothingRunning() {
        RemoteStatusSnapshot s = new RemoteStatusSnapshot(true, "8", 0, 0, 1, 0, 1, 2, 0, 0);
        assertEquals("空闲", s.executionText());
        assertEquals("", s.warningText());
        assertEquals("空闲", s.headline());
    }

    @Test
    public void approvalOutranksQueueInWarning() {
        RemoteStatusSnapshot s = new RemoteStatusSnapshot(true, "8", 0, 3, 1, 2, 1, 2, 1, 0);
        assertEquals("审批 2", s.warningText());
    }

    @Test
    public void backgroundJobsShownWhenIdle() {
        RemoteStatusSnapshot s = new RemoteStatusSnapshot(true, "8", 0, 0, 1, 0, 1, 2, 2, 0);
        assertEquals("后台 2", s.executionText());
        assertEquals("后台 2", s.warningText());
    }

    @Test
    public void missingFieldsDegradeToZeroButStayOnline() {
        RemoteStatusSnapshot s = RemoteStatusSnapshot.parse(new HashMap<String, Object>(), 0L);
        assertEquals(0, s.cliRunning);
        assertEquals("空闲", s.executionText());
        assertTrue("缺 ok 字段不应把状态条判成离线", s.ok);
    }

    @Test
    public void acceptsJsonObjectToo() throws Exception {
        JSONObject root = new JSONObject();
        root.put("cli", new JSONObject().put("running", 0).put("queued", 1));
        root.put("active_jobs", 2);

        RemoteStatusSnapshot s = RemoteStatusSnapshot.parse(root, 1L);

        assertEquals(1, s.cliQueued);
        assertEquals(2, s.activeJobs);
        assertEquals("排队 1", s.executionText());
    }

    @Test
    public void stringNumbersAreTolerated() {
        Map<String, Object> root = map("cli", map("running", "2", "queued", "x"));
        RemoteStatusSnapshot s = RemoteStatusSnapshot.parse(root, 0L);
        assertEquals(2, s.cliRunning);
        assertEquals(0, s.cliQueued);
    }
}
