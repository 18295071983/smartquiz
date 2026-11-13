package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * B15 durable（2026-11-13）：子代理工具轨迹的累积。
 *
 * <p>以前子代理只合成一张"最近动作"提示卡，看不出跑过哪些工具、结果如何，也不耐久。
 * 现在 {@code subagent/activity} 累积成结构化步骤，再由投影落成 {@code subagent_card} 组件
 * （组件随消息落盘 → 重进会话仍是同一张卡）。</p>
 */
public class DshSubagentDurableTest {

    private static DshEvent event(String type, int seq, JSONObject data) {
        JSONObject raw = new JSONObject();
        try {
            raw.put("v", 2);
            raw.put("seq", seq);
            raw.put("type", type);
            raw.put("data", data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return DshEvent.from(raw);
    }

    private static JSONObject activity(String childId, String kind, String key, Object value) {
        JSONObject d = new JSONObject();
        try {
            d.put("child_id", childId);
            d.put("label", "子代理A");
            d.put("kind", kind);
            if (key != null) d.put(key, value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return d;
    }

    @Test
    public void toolCallAndResultBecomeOneStep() {
        DshTimeline t = new DshTimeline();
        t.reduce(event("subagent/activity", 1,
                activity("c1", "tool", "callId", "call-1")));
        // tool 名称与参数单独放（上面的 helper 只带一个键）
        t.reduce(event("subagent/activity", 2, bridge("c1", "call-1", "read_file", "/a.txt")));
        t.reduce(event("subagent/activity", 3, result("c1", "call-1", false, "line1")));

        assertEquals(1, t.subagents().size());
        DshTimeline.Subagent sa = t.subagents().iterator().next();
        assertEquals("c1", sa.childId);
        assertEquals(1, sa.steps.size());
        DshTimeline.SubagentStep st = sa.steps.values().iterator().next();
        assertEquals("read_file", st.tool);
        assertEquals("/a.txt", st.args);
        assertEquals("success", st.status);
        assertEquals("line1", st.result);
    }

    @Test
    public void failedResultMarksStepFailed() {
        DshTimeline t = new DshTimeline();
        t.reduce(event("subagent/activity", 1, bridge("c1", "call-9", "write", "b.txt")));
        t.reduce(event("subagent/activity", 2, result("c1", "call-9", true, "ENOENT")));

        DshTimeline.SubagentStep st = t.subagents().iterator().next()
                .steps.values().iterator().next();
        assertEquals("failed", st.status);
        assertEquals("ENOENT", st.result);
    }

    @Test
    public void twoChildrenAreTrackedSeparately() {
        DshTimeline t = new DshTimeline();
        t.reduce(event("subagent/activity", 1, bridge("c1", "call-1", "read", "a")));
        t.reduce(event("subagent/activity", 2, bridge("c2", "call-2", "write", "b")));

        assertEquals(2, t.subagents().size());
        for (DshTimeline.Subagent sa : t.subagents()) {
            assertEquals("每个子代理应各自 1 步", 1, sa.steps.size());
        }
    }

    @Test
    public void stepsAreCapped() {
        DshTimeline t = new DshTimeline();
        for (int i = 0; i < DshTimeline.MAX_SUBAGENT_STEPS + 5; i += 1) {
            t.reduce(event("subagent/activity", i + 1,
                    bridge("c1", "call-" + i, "tool" + i, "a" + i)));
        }
        assertEquals("超过上限应丢最旧的",
                DshTimeline.MAX_SUBAGENT_STEPS, t.subagents().iterator().next().steps.size());
    }

    @Test
    public void resultWithoutCallIdAttachesToLastRunningStep() {
        DshTimeline t = new DshTimeline();
        t.reduce(event("subagent/activity", 1, bridge("c1", "", "read", "a")));
        JSONObject res = new JSONObject();
        try {
            res.put("child_id", "c1");
            res.put("kind", "tool-result");
            res.put("text", "done");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        t.reduce(event("subagent/activity", 2, res));

        DshTimeline.SubagentStep st = t.subagents().iterator().next()
                .steps.values().iterator().next();
        assertEquals("success", st.status);
        assertEquals("done", st.result);
    }

    @Test
    public void subagentStepsSurviveBubbleSwitch() {
        DshTimeline first = new DshTimeline();
        first.reduce(event("subagent/activity", 1, bridge("c1", "call-1", "read", "a")));
        first.reduce(event("subagent/activity", 2, result("c1", "call-1", false, "ok")));

        // 换气泡：Activity 会新建时间线并继承
        DshTimeline second = new DshTimeline();
        second.adoptSubagentsFrom(first);

        DshTimeline.Subagent sa = second.subagents().iterator().next();
        assertEquals("换气泡后子代理仍应在", "c1", sa.childId);
        assertEquals("步骤不能丢", 1, sa.steps.size());
        assertEquals("success", sa.steps.values().iterator().next().status);
    }

    // ---- helpers：构造带多个字段的合成帧 ----

    private static JSONObject bridge(String childId, String callId, String tool, String args) {
        JSONObject d = new JSONObject();
        try {
            d.put("child_id", childId);
            d.put("label", "子代理A");
            d.put("kind", "tool");
            d.put("callId", callId == null ? "" : callId);
            d.put("tool", tool);
            d.put("args", args);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return d;
    }

    private static JSONObject result(String childId, String callId, boolean error, String text) {
        JSONObject d = new JSONObject();
        try {
            d.put("child_id", childId);
            d.put("kind", "tool-result");
            d.put("toolCallId", callId == null ? "" : callId);
            d.put("error", error);
            d.put("text", text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return d;
    }
}
