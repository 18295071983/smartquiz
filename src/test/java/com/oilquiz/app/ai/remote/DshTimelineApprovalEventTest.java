package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 从**持久审批事件**出卡（2026-11-13）。
 *
 * <p>为什么不用 `approval/request`：它是 agent 作用域 waterfall，插件根上下文收不到
 * （实测 `/status.question_relay.seen=0` 同源问题）。而 `approval/asked` 是落盘审计事件，
 * 形状 `{id, toolName, callId?, reason?}`，一定能收到；`approval/decided` 配对收卡。</p>
 */
public class DshTimelineApprovalEventTest {

    private static DshEvent event(String type, JSONObject data) {
        JSONObject raw = new JSONObject();
        try {
            raw.put("v", 2);
            raw.put("type", type);
            raw.put("data", data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return DshEvent.from(raw);
    }

    private static JSONObject asked(String id, String tool, String callId, String reason) {
        JSONObject d = new JSONObject();
        try {
            d.put("id", id);
            d.put("toolName", tool);
            if (callId != null) d.put("callId", callId);
            if (reason != null) d.put("reason", reason);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return d;
    }

    @Test
    public void askedProducesApprovalAction() {
        DshTimeline t = new DshTimeline();
        DshTimeline.Update u = t.reduce(event("approval/asked",
                asked("ap-1", "write_file", "call-9", "覆盖配置文件")));

        assertEquals(1, u.actions.size());
        DshTimeline.Action a = u.actions.get(0);
        assertEquals("ap-1", a.id);
        assertNotNull(a.request);
        assertEquals("write_file", a.request.optString("toolName"));
        assertEquals("call-9", a.request.optString("callId"));
        assertEquals("覆盖配置文件", a.request.optString("reason"));
    }

    @Test
    public void decidedProducesGoneAction() {
        DshTimeline t = new DshTimeline();
        JSONObject d = new JSONObject();
        try {
            d.put("id", "ap-1");
            d.put("outcome", "allowed-once");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        DshTimeline.Update u = t.reduce(event("approval/decided", d));

        assertEquals(1, u.actions.size());
        assertEquals("ap-1", u.actions.get(0).id);
        assertTrue("decided 应通知收卡", isGone(u.actions.get(0)));
        assertEquals("allowed-once", u.actions.get(0).outcome);
    }

    @Test
    public void emptyIdIsIgnored() {
        DshTimeline t = new DshTimeline();
        DshTimeline.Update u = t.reduce(event("approval/asked", asked("", "write_file", null, null)));
        assertTrue("没有 id 的 asked 不该产卡", u.actions.isEmpty());
    }

    private static boolean isGone(DshTimeline.Action a) {
        return a.kind == DshTimeline.ActionKind.APPROVAL_GONE;
    }
}
