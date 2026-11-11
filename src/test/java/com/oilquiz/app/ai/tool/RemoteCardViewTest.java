package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卡片渲染器（"服务端 view → 原生 View"）的回归网。
 *
 * <p>它以前的问题都不是崩溃，而是"看起来能用、信息丢了"：kind 当标题、hint/detail 被吞、
 * 时间线截 3 行不可展开、暗色下卡片底色透明、每次轮询全量重建。这里逐条钉住。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class RemoteCardViewTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void missingTitleFallsBackToChineseInsteadOfRawKind() {
        LinearLayout box = new LinearLayout(ctx());
        RemoteCardView.render(box, viewOf(card("checks",
                "items", listOf(item("error", "host_cwd", "C:\\nope", "先在电脑上创建该目录")))));

        List<String> texts = allText(box);
        assertTrue("标题要走中文兜底：" + texts, texts.contains("自检"));
        assertFalse("不能把 kind 原文漏给用户：" + texts, texts.contains("checks"));
    }

    @Test
    public void checkHintAndDetailAreBothVisible() {
        LinearLayout box = new LinearLayout(ctx());
        RemoteCardView.render(box, viewOf(card("checks",
                "items", listOf(item("error", "host_cwd", "目录不存在", "检查路径配置")))));

        String all = String.join("\n", allText(box));
        assertTrue("现象要显示：" + all, all.contains("目录不存在"));
        assertTrue("建议也要显示（旧实现直接丢掉 hint）：" + all, all.contains("检查路径配置"));
    }

    @Test
    public void cardLevelErrorIsRendered() {
        LinearLayout box = new LinearLayout(ctx());
        Map<String, Object> sessionCard = card("session");
        sessionCard.put("error", "no_session");
        sessionCard.put("detail", "会话日志读取失败");

        RemoteCardView.render(box, viewOf(sessionCard));

        String all = String.join("\n", allText(box));
        assertTrue("读取失败原因必须可见：" + all, all.contains("会话日志读取失败"));
        assertTrue(all, all.contains("no_session"));
    }

    @Test
    public void rerenderWithSameContentKeepsExistingViews() {
        LinearLayout box = new LinearLayout(ctx());
        Map<String, Object> view = viewOf(card("status",
                "rows", listOf(row("插件", "v8"), row("会话", "42 条"))));

        RemoteCardView.render(box, view);
        assertTrue(box.getChildCount() > 0);
        View first = box.getChildAt(0);

        RemoteCardView.render(box, view); // 3 秒轮询：内容没变
        assertSame("内容没变就不该重建（否则展开态/滚动位置丢失）", first, box.getChildAt(0));
    }

    @Test
    public void breathCardIsReusedNotStacked() {
        LinearLayout box = new LinearLayout(ctx());
        RemoteCardView.render(box, viewOf(card("status")));
        int base = box.getChildCount();

        RemoteCardView.appendBreath(box, true, 42, 0, null);
        RemoteCardView.appendBreath(box, true, 55, 1, null);
        RemoteCardView.appendBreath(box, false, 0, 2, "timeout");

        assertEquals("轮询多少次都只该有一张呼吸卡", base + 1, box.getChildCount());
        String all = String.join("\n", allText(box));
        assertTrue("应显示最后一次心跳结果：" + all, all.contains("timeout"));
    }

    @Test
    public void timelineMessageCanExpandBeyondThreeLines() {
        LinearLayout box = new LinearLayout(ctx());
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("origin", "phone");
        msg.put("text", longText());
        RemoteCardView.render(box, viewOf(card("timeline", "messages", listOf(msg))));

        TextView text = findMessageTextView(box);
        assertTrue(text != null);
        assertEquals("默认预览 3 行", 3, text.getMaxLines());

        text.performClick();
        assertEquals("点一下展开全文", Integer.MAX_VALUE, text.getMaxLines());
        text.performClick();
        assertEquals("再点收起", 3, text.getMaxLines());
    }

    // ===== 构造 view / 遍历 =====

    @SafeVarargs
    private static Map<String, Object> viewOf(Map<String, Object>... cards) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", "汽水音乐");
        header.put("badge", "已连接");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("header", header);
        view.put("cards", new ArrayList<>(Arrays.asList(cards)));
        return view;
    }

    private static Map<String, Object> card(String kind, Object... kv) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("kind", kind);
        for (int i = 0; i + 1 < kv.length; i += 2) c.put(String.valueOf(kv[i]), kv[i + 1]);
        return c;
    }

    private static Map<String, Object> item(String state, String name, String detail, String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("name", name);
        m.put("detail", detail);
        m.put("hint", hint);
        return m;
    }

    private static Map<String, Object> row(String label, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", value);
        return m;
    }

    @SafeVarargs
    private static <T> List<T> listOf(T... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static String longText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i += 1) sb.append("第 ").append(i + 1).append(" 行内容\n");
        return sb.toString().trim();
    }

    private static List<String> allText(ViewGroup root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(View v, List<String> out) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) out.add(t.toString());
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i += 1) collect(g.getChildAt(i), out);
        }
    }

    /** 时间线那条正文：唯一可点击的 TextView */
    private static TextView findMessageTextView(ViewGroup root) {
        return deepestClickableText(root);
    }

    private static TextView deepestClickableText(View v) {
        if (v instanceof TextView && v.isClickable()) return (TextView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i += 1) {
                TextView found = deepestClickableText(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
