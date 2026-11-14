package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件绑定覆盖率：DSH 官方 {@code known-event-types.ts} 里的**每一种**事件，
 * 手机端都必须有明确归属 —— 要么出卡/落正文，要么登记为"纯内部、不渲染"。
 *
 * <p>2026-11-14 对照发现：63 种里有 13 种只是塞进 {@code IGNORED_TYPES}（不渲染也不报未知），
 * 3 种（{@code subagent/catalog}、{@code feedback/record}、
 * {@code session-log-deepseek/delivery-accepted}）连忽略名单都没进，一直在污染
 * "未知事件"计数；其中 {@code subagent/catalog}、{@code agent/inbox/spliced}、
 * {@code tool/ptc-dispatch*}、{@code hook/*}、{@code image/offload}、{@code model/selection}
 * 其实是用户该看到的信息，现在都出了卡。</p>
 *
 * <p>这份清单要与 DSH 安装包的
 * {@code dsh/node_modules/@deepseek-ai/dsh-session/src/known-event-types.ts} 同步；
 * 升级 DSH 后先跑这个测试，缺哪个补哪个。</p>
 */
public class DshEventCoverageTest {

    /** 与 DSH 0.2.2-rc.3 的 KNOWN_SESSION_EVENT_TYPES 一致（63 种） */
    private static final String[] KNOWN = {
            "agent-preset/selected", "agent/inbox/spliced", "approval/asked", "approval/decided",
            "approval/policy", "assistant/attempt", "assistant/message", "command/done",
            "command/run", "compaction/end", "compaction/prune", "compaction/start",
            "compaction/summary", "deliverables/presented", "developer/message",
            "feedback/message-delete", "feedback/message-put", "feedback/record", "goal/change",
            "hook/invoked", "hook/result", "image/offload", "job/registered", "job/settled",
            "llm/retry", "llm/retry-started", "model/selection", "permission/preset", "plan/mode",
            "request/context", "request/header", "sandbox/mode", "schedule/change",
            "session-log-deepseek/delivery-accepted", "session/end-seed", "session/title",
            "session/title-llm-request", "step/end", "step/start", "subagent/catalog",
            "subagent/descriptor", "subagent/model-selection-policy", "system/message",
            "team/member", "team/message/delivered", "team/message/queued", "team/mode",
            "team/task", "todo/write", "tool-workflow/agent-end", "tool-workflow/agent-start",
            "tool-workflow/run-end", "tool-workflow/run-start", "tool/call", "tool/ptc-dispatch",
            "tool/ptc-dispatch-start", "tool/result", "turn/end", "turn/start", "user/message",
            "web/deepseek-search-llm-request", "working-directory/change", "workspace/changes",
    };

    @Test
    public void everyKnownEventTypeIsBound() {
        DshTimeline timeline = new DshTimeline();
        for (int i = 0; i < KNOWN.length; i += 1) {
            timeline.reduce(DshEvent.from(event(KNOWN[i], i + 1, dataFor(KNOWN[i]))));
        }
        List<String> unknown = new ArrayList<>(timeline.unknownTypes().keySet());
        assertEquals("未绑定的事件类型：" + unknown, 0, unknown.size());
        assertEquals(0, timeline.unknownEventKinds());
    }

    @Test
    public void inboxSpliceBecomesQueueCard() {
        DshCards.Card card = cardOf("agent/inbox/spliced", splice("next-step", "先看测试目录"));
        assertNotNull("插话入队应出卡", card);
        assertEquals("alert_card", card.type);
        assertEquals("插话已入队", card.props.optString("title"));
        assertTrue("next-step 应说明下一步生效",
                card.props.optString("content").contains("下一步"));
        assertTrue("应带上插话内容", card.props.optString("content").contains("先看测试目录"));
        // 点击行为：滚到末尾那条（正在排队/执行的气泡）
        assertEquals("last", card.props.optString("jump"));
    }

    @Test
    public void inboxSpliceNextTurnSaysAfterThisRound() {
        DshCards.Card card = cardOf("agent/inbox/spliced", splice("next-turn", "跑完再看"));
        assertNotNull(card);
        assertTrue(card.props.optString("content").contains("本轮结束后"));
    }

    /**
     * 2026-11-14：子代理**一创建**就要有轨迹卡 —— 以前只有 subagent/activity 才建条目，
     * 刚启动还没跑工具时甲板卡槽是空的，"跳过去看轨迹"也无处可跳。
     * 同时 subagent/catalog 不再产出通用提示卡（同一件事不出两张卡）。
     */
    @Test
    public void subagentCatalogRegistersTraceCardImmediately() {
        DshTimeline timeline = new DshTimeline();
        timeline.reduce(DshEvent.from(event("subagent/catalog", 1, payload(
                "childId", "child-1", "label", "调研", "mode", "continuable"))));
        assertEquals("catalog 应立刻登记子代理", 1, timeline.subagents().size());
        for (DshCards.Card c : timeline.orderedCards()) {
            assertTrue("不应再产出 alert_card：" + c.type, !"alert_card".equals(c.type));
        }

        com.oilquiz.app.ai.chat.ChatMessage msg =
                com.oilquiz.app.ai.chat.ChatMessage.createAIMessage("m1", "", 1000L, null, 0, 0);
        DshProjection.project(msg, timeline, key -> "id-" + key, 1000L);
        boolean found = false;
        for (com.oilquiz.app.ai.chat.component.ComponentData c : msg.components) {
            if (c != null && "subagent_card".equals(c.type)) {
                found = true;
                assertEquals("child-1", c.props.optString("childId"));
                assertEquals("continuable", c.props.optString("mode"));
            }
        }
        assertTrue("刚创建的子代理也应有 subagent_card", found);
    }

    @Test
    public void ptcDispatchBecomesNoticeCard() {
        JSONObject data = new JSONObject();
        try {
            data.put("name", "pwsh");
            data.put("callId", "call_1");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        DshCards.Card card = cardOf("tool/ptc-dispatch", data);
        assertNotNull(card);
        assertEquals("代码内调用 · pwsh", card.props.optString("title"));
    }

    @Test
    public void hookAndImageAndModelAreVisible() {
        assertNotNull(cardOf("hook/invoked", payload("name", "pre-step")));
        assertNotNull(cardOf("hook/result", payload("name", "pre-step")));
        assertNotNull(cardOf("image/offload", payload("imageId", "img-1")));
        DshCards.Card model = cardOf("model/selection", payload("model", "deepseek-flash"));
        assertNotNull(model);
        assertEquals("deepseek-flash", model.props.optString("content"));
    }

    // ===== 构造 =====

    private static DshCards.Card cardOf(String type, JSONObject data) {
        DshTimeline timeline = new DshTimeline();
        timeline.reduce(DshEvent.from(event(type, 1, data)));
        List<DshCards.Card> cards = timeline.orderedCards();
        for (DshCards.Card c : cards) {
            if (c != null) return c;
        }
        return null;
    }

    private static JSONObject event(String type, int seq, JSONObject data) {
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

    private static JSONObject splice(String target, String text) {
        JSONObject msg = payload("content", new JSONArray().put(payload("type", "text", "text", text)));
        JSONArray inserted = new JSONArray().put(msg);
        return payload("target", target, "inserted", inserted, "messageId", "m-" + target);
    }

    /** 每种事件给一份"最小可用"载荷：只填该类型必读的字段，其余留空 */
    private static JSONObject dataFor(String type) {
        if (type.startsWith("tool-workflow/") || type.startsWith("team/")) return new JSONObject();
        switch (type) {
            case "agent/inbox/spliced":
                return splice("next-step", "示例插话");
            case "todo/write":
                return payload("todos", new JSONArray().put(payload("content", "写测试", "status", "pending")));
            case "tool/call":
                return payload("callId", "c1", "name", "pwsh", "arguments", "{}");
            case "tool/result":
                return payload("toolCallId", "c1", "isError", false);
            case "assistant/message":
                return payload("message", payload("content",
                        new JSONArray().put(payload("type", "text", "text", "回答"))));
            case "user/message":
                return payload("content", new JSONArray().put(payload("type", "text", "text", "提问")),
                        "source", payload("kind", "user"));
            case "session/title":
                return payload("title", "标题");
            case "subagent/catalog":
                return payload("childId", "child-1", "label", "调研", "mode", "continuable");
            case "subagent/descriptor":
                return payload("label", "调研", "mode", "continuable");
            case "tool/ptc-dispatch":
            case "tool/ptc-dispatch-start":
                return payload("name", "pwsh", "callId", "c1");
            case "hook/invoked":
            case "hook/result":
                return payload("name", "pre-step", "text", "ok");
            case "model/selection":
                return payload("model", "deepseek-flash");
            case "image/offload":
                return payload("imageId", "img-1");
            case "compaction/start":
            case "compaction/end":
                return payload("compactionId", "cp-1");
            case "job/registered":
            case "job/settled":
                return payload("jobId", "j1", "label", "构建");
            case "approval/asked":
            case "approval/decided":
                return payload("id", "a1", "toolName", "pwsh", "outcome", "allowed");
            case "plan/mode":
                return payload("active", true);
            case "goal/change":
                return payload("title", "目标");
            case "deliverables/presented":
                return payload("files", new JSONArray().put(payload("path", "a.txt")));
            case "command/run":
            case "command/done":
                return payload("commandId", "cmd1", "command", "/compact");
            default:
                return new JSONObject();
        }
    }

    private static JSONObject payload(Object... pairs) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                o.put(String.valueOf(pairs[i]), pairs[i + 1]);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return o;
    }
}
