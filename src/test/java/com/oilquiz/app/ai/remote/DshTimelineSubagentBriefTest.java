package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 子代理活动摘要（2026-11-13，B15）。
 *
 * <p>根会话收不到子会话的 durable `tool/result`，只能靠插件合成的 `subagent/activity`。
 * 插件现在会把结果正文放进 `text`，这里钉住"摘要里要带上结果"，否则子代理工具完成时
 * 手机只看到"工具完成"四个字，看不出做了什么。</p>
 */
public class DshTimelineSubagentBriefTest {

    private static JSONObject activity(String kind, JSONObject extra) {
        JSONObject d = new JSONObject();
        try {
            d.put("child_id", "child-1");
            d.put("label", "子代理A");
            d.put("kind", kind);
            if (extra != null) {
                for (String k : new String[]{"text", "tool", "args", "error", "step", "stop"}) {
                    if (extra.has(k)) d.put(k, extra.get(k));
                }
            }
            JSONObject raw = new JSONObject();
            raw.put("v", 2);
            raw.put("type", "subagent/activity");
            raw.put("data", d);
            return raw;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String cardContent(DshTimeline timeline) {
        for (DshCards.Card c : timeline.orderedCards()) {
            try {
                Object content = c.props.get("content");
                if (content != null) return String.valueOf(content);
            } catch (Exception ignored) {
                // 这张卡没有 content 或类型不符：继续找下一张
            }
        }
        return "";
    }

    @Test
    public void toolResultBriefCarriesText() {
        JSONObject extra = new JSONObject();
        try {
            extra.put("text", "wrote 12 lines");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        DshTimeline t = new DshTimeline();
        t.reduce(DshEvent.from(activity("tool-result", extra)));
        String content = cardContent(t);
        assertTrue("摘要应带结果正文：" + content, content.contains("wrote 12 lines"));
        assertTrue("应标明完成：" + content, content.contains("工具完成"));
    }

    @Test
    public void failedResultMarksFailure() {
        JSONObject extra = new JSONObject();
        try {
            extra.put("text", "ENOENT");
            extra.put("error", true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        DshTimeline t = new DshTimeline();
        t.reduce(DshEvent.from(activity("tool-result", extra)));
        String content = cardContent(t);
        assertTrue("应标明失败：" + content, content.contains("工具失败"));
        assertTrue("应带错误正文：" + content, content.contains("ENOENT"));
    }
}
