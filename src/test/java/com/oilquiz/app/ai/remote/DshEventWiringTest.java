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
        assertEquals("dsh_command", card.type);
        assertEquals("success", card.props.optString("status"));
        assertEquals("2026-11-14", card.props.optString("output"));
    }

    // ---------------------------------------------------------------- 后台任务

    @Test
    public void jobRegisteredThenSettledKeepsIdentity() {
        DshTimeline t = feedAll(
                envelope("job/registered", merge(located(1, 0), obj("jobId", "j1", "label", "跑测试"))),
                envelope("job/settled", merge(located(2, 0),
                        obj("jobId", "j1", "label", "跑测试", "status", "completed"))));
        assertEquals("任务卡按 jobId 聚合，跨轮也只能一张", 1, t.orderedCards().size());
        DshCards.Card card = t.orderedCards().get(0);
        assertEquals("dsh_job", card.type);
        assertEquals("跑测试", card.props.optString("label"));
        // 事件里的 status 原样透传（这里是 "completed"）；渲染层把
        // success / completed / done 一律画成"已完成"（DshCardViews.statusLabel），
        // 所以这里钉的是"不许被映射器改写成别的词"，不是钉某个具体词。
        assertEquals("completed", card.props.optString("status"));
    }

    // ---------------------------------------------------------------- 目标

    @Test
    public void goalChangeBecomesNotice() {
        JSONObject data = located(1, 0);
        try {
            data.put("goal", obj("text", "把问答页接完"));
            data.put("operation", "updated");
            data.put("roundsStarted", 3);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        DshTimeline t = feed("goal/change", data);
        DshCards.Card card = cardOfType(t, "dsh_notice");
        assertNotNull(card);
        assertEquals("当前目标", card.props.optString("title"));
        assertTrue("正文要带目标文本", card.props.optString("content").contains("把问答页接完"));
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
        DshCards.Card card = cardOfType(t, "dsh_notice");
        assertNotNull(card);
        assertEquals("文件变更", card.props.optString("title"));
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
