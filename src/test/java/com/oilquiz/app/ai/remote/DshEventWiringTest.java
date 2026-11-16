package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * M9 事件接线的回归网（2026-11-14）。
 *
 * <p>设计稿 §5 一度断言"todo/write、command/*、job/*、goal/*、deliverables、workspace/changes
 * 全是收到但扔掉"。实际读代码发现 reducer 的 {@code default} 分支已经把
 * {@link DshCards#handles(String)} 认的类型交给 {@link DshCards#map} 了 —— 也就是说这些卡
 * <b>本来就有</b>，只是没人盯。这里按"一类事件一条 fixture"把它们钉住，顺带把
 * "卡片 type/props 长什么样"写成可读的契约。</p>
 *
 * <p>只测纯逻辑（reducer + projection），不碰 View。</p>
 */
public class DshEventWiringTest {

    private static int seq;

    private static JSONObject envelope(String type, JSONObject data) {
        seq += 1;
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

    /** 把事件喂给时间线，返回它。 */
    private static DshTimeline feed(String type, JSONObject data) {
        DshTimeline t = new DshTimeline();
        t.reduce(DshEvent.from(envelope(type, data)));
        return t;
    }

    private static DshTimeline feedAll(JSONObject... events) {
        DshTimeline t = new DshTimeline();
        for (JSONObject ev : events) t.reduce(DshEvent.from(ev));
        return t;
    }

    private static DshCards.Card cardOfType(DshTimeline t, String type) {
        for (DshCards.Card c : t.orderedCards()) {
            if (type.equals(c.type)) return c;
        }
        return null;
    }

    // ---------------------------------------------------------------- 待办

    @Test
    public void todoWriteBecomesTodoCard() {
        JSONObject data = located(1, 0);
        JSONArray todos = new JSONArray();
        todos.put(obj("content", "写迁移脚本", "status", "completed"));
        todos.put(obj("content", "跑一遍回归", "status", "pending"));
        try {
            data.put("todos", todos);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        DshTimeline t = feed("todo/write", data);
        DshCards.Card card = cardOfType(t, "todo_card");
        assertNotNull("待办事件应出 todo_card", card);
        JSONArray items = card.props.optJSONArray("items");
        assertNotNull(items);
        assertEquals(2, items.length());
        assertEquals("写迁移脚本", items.optJSONObject(0).optString("text"));
        assertTrue("completed 要折成 done", items.optJSONObject(0).optBoolean("done"));
        assertFalse(items.optJSONObject(1).optBoolean("done"));
    }

    // ---------------------------------------------------------------- 命令

    @Test
    public void commandRunThenDoneUpdatesSameCard() {
        DshTimeline t = feedAll(
                envelope("command/run", merge(located(1, 0),
                        obj("commandId", "c1", "name", "pwsh", "args", "Get-Date"))),
                envelope("command/done", merge(located(1, 0),
                        obj("commandId", "c1", "text", "2026-11-14", "failed", false))));
        assertEquals("同一 commandId 只能有一张卡", 1, t.orderedCards().size());
        DshCards.Card card = t.orderedCards().get(0);
        // 2026-11-14 组件管线合并：命令不再有专属 dsh_command 卡，复用 code_card
        //（等宽 + 复制 + 状态灯，见 DshCards.map 的 command 分支）
        assertEquals("code_card", card.type);
        assertEquals("success", card.props.optString("status"));
        assertEquals("2026-11-14", card.props.optString("output"));
        assertEquals("pwsh Get-Date", card.props.optString("command"));
    }

    // ---------------------------------------------------------------- 后台任务

    /**
     * 后台任务改由甲板 jobs 插槽承载（2026-11-14，73c695d9）：不再进消息流。
     * 这里钉住"不出卡、也不被算成未识别"；生命周期展示在插槽那边（收到更新即改、完成即消失）。
     */
    @Test
    public void jobEventsGoToSlotsNotChat() {
        DshTimeline t = feedAll(
                envelope("job/registered", merge(located(1, 0), obj("jobId", "j1", "label", "跑测试"))),
                envelope("job/settled", merge(located(2, 0),
                        obj("jobId", "j1", "label", "跑测试", "status", "completed"))));
        assertEquals("后台任务不再出聊天卡（改由甲板插槽承载）", 0, t.orderedCards().size());
        assertEquals("已登记为已识别类型，不能污染 unknown 计数", 0, t.unknownEventKinds());
    }

    /**
     * 失败/取消的后台任务要留一张耐久卡（2026-11-16）。
     *
     * <p>插槽是"完成即消失"的，任务失败时用户会什么都看不到；所以失败收尾额外投影一张
     * {@code job} 卡（成功仍只走插槽，见上一条用例）。</p>
     */
    @Test
    public void jobFailureLeavesDurableCard() {
        DshTimeline t = feedAll(
                envelope("job/registered", merge(located(1, 0), obj("jobId", "j1", "label", "跑测试"))),
                envelope("job/settled", merge(located(2, 0),
                        obj("jobId", "j1", "label", "跑测试", "status", "failed"))));
        assertEquals("失败任务必须留一张卡", 1, t.orderedCards().size());
        DshCards.Card card = t.orderedCards().get(0);
        assertEquals("job", card.type);
        assertEquals("job-j1", card.key);
        assertEquals("跑测试", card.props.optString("job_label"));
        assertEquals("failed", card.props.optString("status"));
    }

    // ---------------------------------------------------------------- 目标

    /** 目标状态同样搬进甲板 goal 插槽（73c695d9），消息流里不再出卡。 */
    @Test
    public void goalChangeGoesToSlotNotChat() {
        JSONObject data = located(1, 0);
        try {
            data.put("goal", obj("text", "把问答页接完"));
            data.put("operation", "updated");
            data.put("roundsStarted", 3);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        DshTimeline t = feed("goal/change", data);
        assertEquals("目标状态不再出聊天卡（改由甲板插槽承载）", 0, t.orderedCards().size());
        assertEquals(0, t.unknownEventKinds());
    }

    // ---------------------------------------------------------------- 产物

    @Test
    public void deliverablesBecomeFileList() {
        JSONObject data = located(1, 0);
        JSONArray files = new JSONArray();
        files.put(obj("path", "D:/out/报告.docx"));
        try {
            data.put("files", files);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        DshTimeline t = feed("deliverables/presented", data);
        DshCards.Card card = cardOfType(t, "file_list");
        assertNotNull(card);
        JSONArray out = card.props.optJSONArray("files");
        assertEquals(1, out.length());
        assertEquals("报告.docx", out.optJSONObject(0).optString("name"));
    }

    // ---------------------------------------------------------------- 工作区

    @Test
    public void workspaceChangesBecomesNotice() {
        DshTimeline t = feed("workspace/changes", located(1, 0));
        // 2026-11-14 组件管线合并：dsh_notice 并入内置 alert_card
        DshCards.Card card = cardOfType(t, "alert_card");
        assertNotNull(card);
        assertEquals("文件变更", card.props.optString("title"));
        assertTrue("正文要说明明细在电脑端",
                card.props.optString("text").contains("电脑"));
    }

    // ---------------------------------------------------------------- 投影

    @Test
    public void projectionCarriesCardsIntoComponents() {
        JSONObject data = located(1, 0);
        JSONArray todos = new JSONArray();
        todos.put(obj("content", "一条待办", "status", "pending"));
        try {
            data.put("todos", todos);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        JSONObject msgData = located(1, 1);
        try {
            msgData.put("message", obj("content", content("text", "好的")));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        DshTimeline t = feedAll(
                envelope("assistant/message", msgData),
                envelope("todo/write", data));

        ChatMessage msg = ChatMessage.createAIMessage("m1", "", 1000L, null, 0, 0);
        DshProjection.project(msg, t, key -> "id-" + key, 1000L);

        assertNotNull(msg.components);
        boolean found = false;
        for (ComponentData c : msg.components) {
            if ("todo_card".equals(c.type)) {
                found = true;
                assertEquals("dsh", c.source);
                assertNotNull(c.props.optJSONArray("items"));
            }
        }
        assertTrue("投影必须把 todo_card 带进 components（否则界面上根本看不到）", found);
    }

    // ---------------------------------------------------------------- B 档：状态卡 + 原生按钮

    /**
     * 权限三件套改由甲板 permission 插槽承载（2026-11-14，73c695d9）：
     * 消息流不再合成"可写工作区 · 审批…"卡，旋钮变化的"原地更新"语义也移到插槽那边
     * （插件 ui/slot + RemoteSlotBridge）。这里钉住"不再出卡、且仍算已识别"。
     */
    @Test
    public void permissionStateLivesInSlotNotChat() {
        DshTimeline t = feedAll(
                envelope("permission/preset", merge(located(1, 0), obj("preset", "workspace-write"))),
                envelope("sandbox/mode", merge(located(1, 0), obj("mode", "workspace-write"))),
                envelope("approval/policy", merge(located(1, 0), obj("policy", "ask"))));
        assertEquals("权限三件套不再出聊天卡（改由甲板插槽承载）", 0, t.orderedCards().size());
        assertEquals("三类都应已识别，不能污染 unknown", 0, t.unknownEventKinds());

        DshTimeline u = feedAll(
                envelope("sandbox/mode", merge(located(1, 0), obj("mode", "read-only"))),
                envelope("approval/policy", merge(located(1, 0), obj("policy", "ask"))),
                envelope("sandbox/mode", merge(located(1, 1), obj("mode", "danger-full-access"))),
                envelope("approval/policy", merge(located(1, 1), obj("policy", "never"))));
        assertEquals("旋钮变化也不该冒卡", 0, u.orderedCards().size());
        assertEquals(0, u.unknownEventKinds());
    }

    /**
     * 团队模式仍是聊天卡（带"看电脑面板"按钮）；agent-preset 已改由插槽承载（73c695d9）。
     */
    @Test
    public void teamModeCarriesPanelButtonAgentPresetLivesInSlot() {
        DshTimeline t = feedAll(
                envelope("team/mode", merge(located(1, 0), obj("mode", "协作"))),
                envelope("agent-preset/selected", merge(located(1, 0), obj("agentPreset", "standard"))));
        assertEquals("只有 team/mode 出卡；agent-preset 已搬进插槽", 1, t.orderedCards().size());
        DshCards.Card card = t.orderedCards().get(0);
        assertEquals("alert_card", card.type);
        JSONArray actions = card.props.optJSONArray("actions");
        assertNotNull("团队模式卡应带按钮", actions);
        assertEquals("insight", actions.optJSONObject(0).optString("action"));
        assertEquals(0, t.unknownEventKinds());
    }

    // ---------------------------------------------------------------- 工具

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try {
                o.put(String.valueOf(kv[i]), kv[i + 1]);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        return o;
    }

    private static JSONObject merge(JSONObject a, JSONObject b) {
        try {
            for (java.util.Iterator<String> it = b.keys(); it.hasNext(); ) {
                String k = it.next();
                a.put(k, b.opt(k));
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return a;
    }

    private static JSONArray content(String type, String text) {
        JSONArray arr = new JSONArray();
        arr.put(obj("type", type, "text", text));
        return arr;
    }
}
