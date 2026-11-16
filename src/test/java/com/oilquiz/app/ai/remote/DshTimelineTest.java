package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.ChatMessage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * 转换器（纯逻辑）的回归网。
 *
 * <p>旧实现的转换规则只能用真机看：一条条 if-else 埋在 Activity 里，没有任何测试。
 * 这里覆盖"事件 → 时间线"的四类契约：位置归组、工具配对、卡片聚合、游标幂等。</p>
 */
public class DshTimelineTest {

    // ===== 构造事件的小工具（与插件 /events 的信封一致） =====

    private static JSONObject envelope(String type, int seq, JSONObject data) {
        JSONObject ev = new JSONObject();
        try {
            ev.put("type", type);
            ev.put("seq", seq);
            ev.put("ts", 1000L + seq);
            if (data != null) ev.put("data", data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return ev;
    }

    private static JSONObject located(int turn, int step) {
        JSONObject d = new JSONObject();
        try {
            d.put("turn", turn);
            d.put("step", step);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return d;
    }

    private static JSONObject assistant(int turn, int step, String text, String thinking) {
        return assistantSeq(10 * turn + step, turn, step, text, thinking);
    }

    private static JSONObject assistantSeq(int seq, int turn, int step, String text, String thinking) {
        JSONObject d = located(turn, step);
        JSONArray content = new JSONArray();
        if (thinking != null) {
            content.put(kv("type", "reasoning", "text", thinking));
        }
        if (text != null) {
            content.put(kv("type", "text", "text", text));
        }
        try {
            d.put("message", kv("content", content));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return envelope("assistant/message", seq, d);
    }

    private static JSONObject toolCall(int turn, int step, String callId, String name, String args) {
        JSONObject d = located(turn, step);
        try {
            d.put("callId", callId);
            d.put("name", name);
            d.put("arguments", args);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return envelope("tool/call", 10 * turn + step + 1, d);
    }

    private static JSONObject toolResult(int turn, int step, String callId, String text, boolean error) {
        JSONObject d = located(turn, step);
        try {
            d.put("toolCallId", callId);
            d.put("isError", error);
            JSONArray content = new JSONArray().put(kv("type", "text", "text", text));
            d.put("message", kv("content", content));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return envelope("tool/result", 10 * turn + step + 2, d);
    }

    private static JSONObject kv(Object... pairs) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            try {
                o.put(String.valueOf(pairs[i]), pairs[i + 1]);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        return o;
    }

    private DshTimeline.Update feed(DshTimeline tl, JSONObject raw) {
        return tl.reduce(DshEvent.from(raw));
    }

    // ===== 归组与文案 =====

    @Test
    public void assistantMessage_splitsReasoningFromAnswer() {
        DshTimeline tl = new DshTimeline();

        DshTimeline.Update u = feed(tl, assistant(1, 1, "答案一", "思考一"));

        assertTrue("正文变化必须触发投影", u.contentChanged);
        assertEquals("答案一", tl.answer());
        assertEquals("思考一", tl.thinking());
        assertEquals(ChatMessage.InferencePhase.GENERATING, u.phase);
        assertEquals(1, tl.orderedSteps().size());
    }

    @Test
    public void stepPhasesProduceProgressText() {
        DshTimeline tl = new DshTimeline();

        DshTimeline.Update start = feed(tl, envelope("step/start", 1, located(1, 3)));
        assertEquals("第 3 步 · 思考中", start.phaseText);
        assertEquals(ChatMessage.InferencePhase.THINKING, start.phase);

        DshTimeline.Update end = feed(tl, envelope("step/end", 2, located(1, 3)));
        assertEquals("第 3 步 · 完成", end.phaseText);
    }

    @Test
    public void eventsWithoutTurn_stayOnLastKnownStep() {
        DshTimeline tl = new DshTimeline();
        feed(tl, assistant(2, 5, "正文", null));

        // 待办事件不带 turn/step：必须挂到 (2,5)，而不是重置成 turn 1
        JSONObject todo = new JSONObject();
        JSONArray items = new JSONArray().put(kv("content", "写测试", "status", "pending"));
        JSONObject data = new JSONObject();
        try {
            data.put("todos", items);
            todo.put("type", "todo/write");
            todo.put("seq", 99);
            todo.put("data", data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertEquals(99, tl.reduce(DshEvent.from(todo)).seq);
        assertEquals(1, tl.orderedCards().size());
        assertEquals("todo-2", tl.orderedCards().get(0).key);
    }

    // ===== 工具配对 =====

    @Test
    public void toolCallAndResult_becomeOneToolCard() {
        DshTimeline tl = new DshTimeline();

        assertTrue(feed(tl, toolCall(1, 1, "call_1", "pwsh", "{\"command\":\"ls\"}")).cardsChanged);
        feed(tl, toolResult(1, 1, "call_1", "a.txt\nb.txt", false));

        DshTimeline.Step step = tl.orderedSteps().get(0);
        assertEquals(1, step.tools.size());
        DshTimeline.Tool tool = step.tools.get("call_1");
        assertNotNull(tool);
        assertEquals("pwsh", tool.name);
        assertEquals("a.txt\nb.txt", tool.result);
        assertTrue(tool.finished);
        assertFalse(tool.error);
    }

    @Test
    public void toolResultWithoutCall_isDroppedInsteadOfPhantomCard() {
        DshTimeline tl = new DshTimeline();

        DshTimeline.Update u = feed(tl, toolResult(1, 1, "ghost", "结果", false));

        assertFalse(u.cardsChanged);
        assertFalse("没有调用就没有工具卡", u.contentChanged);
        assertNull(tl.orderedSteps().isEmpty() ? null
                : tl.orderedSteps().get(0).tools.get("ghost"));
    }

    @Test
    public void toolFailureCarriesErrorFlag() {
        DshTimeline tl = new DshTimeline();
        feed(tl, toolCall(1, 1, "c1", "read_file", "{}"));
        feed(tl, toolResult(1, 1, "c1", "no such file", true));

        assertTrue(tl.orderedSteps().get(0).tools.get("c1").error);
    }

    // ===== 卡片 =====

    @Test
    public void todoWrite_upsertsSingleCardInsteadOfAccumulating() {
        DshTimeline tl = new DshTimeline();
        feed(tl, todo(1, 1, "第一项"));
        feed(tl, todo(1, 2, "第二项"));

        assertEquals("同一个 turn 只留一张待办卡", 1, tl.orderedCards().size());
        DshCards.Card card = tl.orderedCards().get(0);
        assertEquals("todo_card", card.type);
        assertEquals("todo-1", card.key);
        assertEquals(1, card.props.optJSONArray("items").length());
        assertEquals("第二项", card.props.optJSONArray("items").optJSONObject(0).optString("text"));
    }

    /**
     * 卡片身份与聚合（2026-11-16 订正）。
     *
     * <p>原用例用的是 {@code job/registered}，但 2026-11-14（73c695d9）起后台任务整类搬进
     * 甲板 jobs 插槽、消息流不再出卡 —— 旧断言必然为 0。这里换成语义同类的
     * {@code team/task}（按 taskId 建 key）继续钉这两条不变量：</p>
     * <ul>
     *   <li>没有稳定身份时按 turn 兜底：同一 turn 只留一张（后到覆盖），跨 turn 各一张；</li>
     *   <li>有稳定身份时跨轮只占一张卡，后到的事件原地更新。</li>
     * </ul>
     */
    @Test
    public void cardsAreKeptPerTurnWhenNoStableId() {
        DshTimeline tl = new DshTimeline();
        feed(tl, todo(1, 1, "下载 A"));
        feed(tl, todo(1, 2, "下载 B"));
        feed(tl, todo(2, 3, "转码 C"));

        assertEquals("同一 turn 只留一张卡，跨 turn 各一张", 2, tl.orderedCards().size());
        DshCards.Card first = tl.orderedCards().get(0);
        assertEquals("todo_card", first.type);
        assertEquals("todo-1", first.key);
        assertEquals("后到的那条覆盖前一条", "下载 B",
                first.props.optJSONArray("items").optJSONObject(0).optString("text"));
    }

    @Test
    public void cardKeyedByIdentity_survivesAcrossTurns() {
        DshTimeline tl = new DshTimeline();
        feed(tl, teamTask(1, 1, "t-1", "整理依赖", "running"));
        feed(tl, teamTask(2, 2, "t-1", "整理依赖", "done"));

        assertEquals("同一身份跨轮只占一张卡", 1, tl.orderedCards().size());
        assertEquals("team-task-t-1", tl.orderedCards().get(0).key);
        assertTrue("后到的事件应原地更新（标题带上新状态）: "
                        + tl.orderedCards().get(0).props,
                tl.orderedCards().get(0).props.optString("title").contains("done"));
    }

    @Test
    public void replayingSameSeq_doesNotDoubleCount() {
        DshTimeline tl = new DshTimeline();
        JSONObject one = todo(1, 7, "任务");

        feed(tl, one);
        assertEquals(1, tl.orderedCards().size());
        assertEquals("首条待办卡只显示自己的内容", "任务",
                tl.orderedCards().get(0).props.optJSONArray("items")
                        .optJSONObject(0).optString("text"));

        feed(tl, one); // 断线重连，服务端从 since 重放
        assertEquals("重放不能多出一张卡", 1, tl.orderedCards().size());
        assertEquals("任务", tl.orderedCards().get(0).props.optJSONArray("items")
                .optJSONObject(0).optString("text"));
    }

    @Test
    public void unknownEvent_isCountedNotSilentlyCrashing() {
        DshTimeline tl = new DshTimeline();
        feed(tl, envelope("weird/new-event", 1, located(1, 1)));
        feed(tl, envelope("weird/new-event", 2, located(1, 1)));
        feed(tl, envelope("weird/another", 3, located(1, 1)));

        assertEquals(2, tl.unknownEventKinds());
        assertEquals(Integer.valueOf(2), tl.unknownTypes().get("weird/new-event"));
    }

    // ===== 审批 =====

    @Test
    public void approvalEmitsActionInsteadOfMessage() {
        DshTimeline tl = new DshTimeline();
        JSONObject ev = new JSONObject();
        try {
            ev.put("type", "approval");
            ev.put("id", "ap_1");
            ev.put("request", kv("tool", "write_file", "prompt", "允许写文件？"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        DshTimeline.Update u = tl.reduce(DshEvent.from(ev));

        assertEquals(1, u.actions.size());
        assertEquals(DshTimeline.ActionKind.APPROVAL, u.actions.get(0).kind);
        assertEquals("ap_1", u.actions.get(0).id);
        assertEquals("write_file", u.actions.get(0).request.optString("tool"));
        assertFalse(u.contentChanged);
    }

    @Test
    public void turnEndMarksEnded() {
        DshTimeline tl = new DshTimeline();
        DshTimeline.Update u = feed(tl, envelope("turn/end", 9, located(1, 1)));
        assertTrue(u.ended);
    }

    @Test
    public void seqOnlyMovesForward() {
        DshTimeline tl = new DshTimeline();
        feed(tl, envelope("step/start", 5, located(1, 1)));
        assertEquals(5, tl.seq());
        feed(tl, envelope("step/end", 3, located(1, 1))); // 迟到的旧事件
        assertEquals(5, tl.seq());
    }

    @Test
    public void streamDeltaAppendsThenMessageReplaces() {
        DshTimeline tl = new DshTimeline();
        feed(tl, delta(1, 1, "text", "你"));
        feed(tl, delta(1, 1, "text", "好"));
        assertEquals("你好", tl.answer());

        // 权威累计消息到达：等长替换，不重复追加
        feed(tl, assistant(1, 1, "你好", null));
        assertEquals("你好", tl.answer());
    }

    @Test
    public void planModeEventTurnsIntoStateAndCard() {
        DshTimeline tl = new DshTimeline();
        JSONObject ev = envelope("plan/mode", 1, located(1, 1));
        try {
            ev.getJSONObject("data").put("active", true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        DshTimeline.Update u = feed(tl, ev);

        assertTrue(u.cardsChanged);
        assertTrue(tl.isPlanActive());
        boolean hasPlanCard = false;
        for (DshCards.Card c : tl.orderedCards()) {
            if ("plan".equals(c.key)) hasPlanCard = true;
        }
        assertTrue("计划模式要有一张状态卡", hasPlanCard);
    }

    @Test
    public void subagentActivityUpdatesDurableState() {
        DshTimeline tl = new DshTimeline();
        feed(tl, subagentActivity(2, "child-1", "调研", "text", "正在整理资料", null));

        DshTimeline.Subagent sa = null;
        for (DshTimeline.Subagent s : tl.subagents()) {
            if ("child-1".equals(s.childId)) sa = s;
        }
        // B15 durable（2026-11-13）：子代理不再合成"最近动作"提示卡，
        // 而是累积成结构化轨迹，投影成 subagent_card 后随消息落盘。
        assertNotNull("子代理活动应进入耐久轨迹", sa);
        assertEquals("调研", sa.label);
        assertEquals("正在整理资料", sa.lastText);
    }

    @Test
    public void scheduleChangedRequestsRefresh() {
        DshTimeline tl = new DshTimeline();
        DshTimeline.Update u = feed(tl, envelope("schedule/changed", 1, null));
        assertTrue("日程变化要通知页面重拉", u.scheduleChanged);
    }

    // ===== helpers：卡片事件 =====

    private static JSONObject todo(int turn, int step, String text) {
        JSONObject d = located(turn, step);
        try {
            d.put("todos", new JSONArray().put(kv("content", text, "status", "pending")));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return envelope("todo/write", 20 + step, d);
    }

    private static JSONObject teamTask(int turn, int step, String taskId, String title, String status) {
        JSONObject d = located(turn, step);
        try {
            d.put("taskId", taskId);
            d.put("title", title);
            d.put("status", status);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return envelope("team/task", 40 + step, d);
    }

    private static JSONObject delta(int turn, int step, String kind, String text) {
        JSONObject d = located(turn, step);
        try {
            d.put("kind", kind);
            d.put("text", text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return envelope("stream/delta", -1, d);
    }

    private static JSONObject subagentActivity(int seq, String childId, String label,
                                               String kind, String text, String tool) {
        JSONObject d = new JSONObject();
        try {
            d.put("child_id", childId);
            d.put("label", label);
            d.put("kind", kind);
            if (text != null) d.put("text", text);
            if (tool != null) d.put("tool", tool);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return envelope("subagent/activity", seq, d);
    }

    @Test
    public void cardKnowsItsOwnRound() {
        DshTimeline tl = new DshTimeline();
        feed(tl, assistant(1, 1, "第一步", null));
        feed(tl, assistant(1, 2, "第二步", null));
        feed(tl, todo(1, 2, "第二步的待办"));

        assertEquals(2, tl.currentRound());
        assertEquals("卡片要记住自己属于第 2 步", 2, tl.roundOfCard("todo-1"));
    }

    @Test
    public void orderedStepsSortByTurnThenStep() {
        DshTimeline tl = new DshTimeline();
        // seq 严格递增（真实事件流如此），但 turn/step 到达顺序故意乱掉：
        // 投影必须按 (turn, step) 排序，而不是按"谁先到"
        feed(tl, assistantSeq(21, 2, 2, "后", null));
        feed(tl, assistantSeq(22, 1, 2, "先二", null));
        feed(tl, assistantSeq(23, 1, 1, "先一", null));

        List<DshTimeline.Step> steps = tl.orderedSteps();
        assertEquals(3, steps.size());
        assertEquals("先一", steps.get(0).text);
        assertEquals("先二", steps.get(1).text);
        assertEquals("后", steps.get(2).text);
    }
}
