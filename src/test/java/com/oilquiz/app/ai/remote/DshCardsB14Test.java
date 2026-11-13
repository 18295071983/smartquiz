package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * B14 渲染侧（2026-11-13）：目录里登记过、手机却完全没渲染的事件类型。
 *
 * <p>这些用例钉住三件事：</p>
 * <ul>
 *   <li>`system/message` / `team/task` 能变成可读卡片，而不是被记成"未识别"；</li>
 *   <li>纯内部事件（`request/header`）不渲染、**也不计入** unknown 计数；</li>
 *   <li>`schedule/change`（与 `schedule/changed` 两种写法）都会置 scheduleChanged。</li>
 * </ul>
 */
public class DshCardsB14Test {

    private static DshEvent event(String type, int seq, JSONObject data) {
        JSONObject raw = new JSONObject();
        try {
            raw.put("v", 2);
            raw.put("seq", seq);
            raw.put("type", type);
            raw.put("data", data == null ? new JSONObject() : data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return DshEvent.from(raw);
    }

    private static String cardContent(DshTimeline timeline) {
        for (DshCards.Card c : timeline.orderedCards()) {
            try {
                // dsh_notice 的渲染器读 text，其次 content（与 DshNoticeCard 一致）
                Object content = c.props.opt("content");
                if (content == null) content = c.props.opt("text");
                if (content != null) return String.valueOf(content);
            } catch (Exception ignored) {
                // 下一张
            }
        }
        return "";
    }

    @Test
    public void systemMessageBecomesNotice() {
        JSONObject data = new JSONObject();
        try {
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text", "系统提示：配额已用完"));
            data.put("message", new JSONObject().put("content", content));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        DshTimeline t = new DshTimeline();
        t.reduce(event("system/message", 1, data));

        assertEquals("已渲染的类型不应计入未识别", 0, t.unknownEventKinds());
        assertTrue("系统消息应变成可读卡片：" + cardContent(t),
                cardContent(t).contains("配额已用完"));
    }

    @Test
    public void teamTaskBecomesNotice() {
        JSONObject data = new JSONObject();
        try {
            data.put("taskId", "task-1");
            data.put("title", "整理依赖");
            data.put("status", "running");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        DshTimeline t = new DshTimeline();
        t.reduce(event("team/task", 1, data));

        assertEquals(0, t.unknownEventKinds());
        assertTrue("团队任务卡应带标题与状态：" + t.orderedCards(),
                cardContent(t).contains("整理依赖"));
    }

    @Test
    public void internalTypesAreNotCountedAsUnknown() {
        DshTimeline t = new DshTimeline();
        t.reduce(event("request/header", 1, null));
        t.reduce(event("hook/invoked", 2, null));
        t.reduce(event("tool/ptc-dispatch", 3, null));

        assertEquals("内部事件不应污染未识别计数", 0, t.unknownEventKinds());
        assertEquals("内部事件不应产生卡片", 0, t.orderedCards().size());
    }

    @Test
    public void scheduleChangeBothSpellingsToggleFlag() {
        DshTimeline a = new DshTimeline();
        assertTrue("schedule/change 应置位", a.reduce(event("schedule/change", 1, null)).scheduleChanged);
        DshTimeline b = new DshTimeline();
        assertTrue("schedule/changed 仍应置位", b.reduce(event("schedule/changed", 1, null)).scheduleChanged);
    }

    @Test
    public void unknownStillCountedWhenNotIgnored() {
        DshTimeline t = new DshTimeline();
        t.reduce(event("brand/new/type", 1, null));
        assertEquals("真正未知的类型仍要计数", 1, t.unknownEventKinds());
    }
}
