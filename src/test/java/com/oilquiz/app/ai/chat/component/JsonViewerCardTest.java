package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * JSON 查看卡的适配（2026-10-21）。
 *
 * <p>两件事必须成立，否则用户看到的又是"一段贴上去的原文"：</p>
 * <ol>
 *   <li><b>字符串形态的 JSON 也要美化</b>——工具结果常把 JSON 序列化后当字符串返回，
 *       旧实现只认 {@code JSONObject}/{@code JSONArray}，字符串走 {@code String.valueOf} 原样吐，
 *       渲染成一整行不换行的转义文本；</li>
 *   <li><b>外壳必须是 {@link CardShell}</b>——旧实现自绘 LinearLayout + 背景，是
 *       "组件和聊天页不是一套语言"（component-system-review §2 P0）的残留实例。</li>
 * </ol>
 *
 * <p>走真渲染路径（{@link ComponentRegistry#render}），不是只测私有工具方法。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class JsonViewerCardTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void stringJsonIsPrettyPrinted() {
        View v = render(obj("data", "{\"city\":\"北京\",\"temp\":25}"));
        String text = firstMonospaceText(v);
        assertNotNull("卡里应有 JSON 文本区", text);
        assertTrue("字符串 JSON 必须被美化成多行，实际: " + oneLine(text), text.contains("\n"));
        assertTrue("应带缩进键值，实际: " + oneLine(text), text.contains("\"city\": \"北京\""));
        assertTrue(text.contains("\"temp\": 25"));
    }

    @Test
    public void stringJsonArrayIsPrettyPrinted() {
        View v = render(obj("data", "[{\"n\":1},{\"n\":2}]"));
        String text = firstMonospaceText(v);
        assertTrue("数组字符串也要多行", text.contains("\n"));
        assertTrue(text.contains("\"n\": 1"));
    }

    /** 已经是对象/数组的 data 仍按老口径美化（不许回归）。 */
    @Test
    public void objectAndArrayDataStillPrettyPrinted() {
        JSONObject nested = new JSONObject();
        try {
            nested.put("k", "v");
        } catch (org.json.JSONException e) {
            throw new RuntimeException(e);
        }
        assertTrue(firstMonospaceText(render(obj("data", nested))).contains("\"k\": \"v\""));

        JSONArray arr = new JSONArray();
        arr.put("a");
        assertTrue(firstMonospaceText(render(obj("data", arr))).contains("\"a\""));
    }

    /** 真·纯文本不是 JSON：原样显示，不许渲染成空白卡。 */
    @Test
    public void plainTextIsShownAsIs() {
        String text = firstMonospaceText(render(obj("data", "命令执行完成")));
        assertEquals("命令执行完成", text.trim());
    }

    /** 看着像 JSON 但解析不了：按原文显示，不吞内容、不崩。 */
    @Test
    public void malformedJsonFallsBackToRawText() {
        String text = firstMonospaceText(render(obj("data", "{\"a\": }")));
        assertTrue("解析失败要保留原文，实际: " + text, text.contains("\"a\""));
    }

    /** 外壳统一到 CardShell（不再自绘卡片背景）。 */
    @Test
    public void rendersInsideCardShell() {
        View v = render(obj("data", "{\"a\":1}"));
        assertTrue("json 卡应走 CardShell 统一外壳", v instanceof CardShell);
    }

    /** data 为空白：走空态，不抛、不渲染空白正文。 */
    @Test
    public void blankDataRendersWithoutCrash() {
        View v = render(obj("data", "   "));
        assertNotNull(v);
    }

    /** json 是通用类型：短名 `json` 也要能出正卡（目录教的就是短名）。 */
    @Test
    public void shortNameResolvesToJsonCard() {
        ChatComponent c = ComponentRegistry.getInstance().componentFor("json");
        assertNotNull("短名 json 解析不到实现（会掉原始 JSON 兜底）", c);
        assertTrue(c instanceof JsonViewerCard);
    }

    // ---- helpers ----

    private View render(JSONObject props) {
        View v = ComponentRegistry.getInstance().render(ctx, ComponentData.of("json_viewer", props));
        assertNotNull("渲染返回 null", v);
        return v;
    }

    /** 取卡里限高滚动区内的正文 TextView（JSON 文本区就是它的唯一内容）。 */
    private static String firstMonospaceText(View root) {
        if (root instanceof CardShell.MaxHeightScrollView) {
            String[] holder = new String[1];
            collectFirstText(root, holder);
            return holder[0];
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                String found = firstMonospaceText(vg.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void collectFirstText(View v, String[] holder) {
        if (holder[0] != null) {
            return;
        }
        if (v instanceof TextView) {
            holder[0] = ((TextView) v).getText().toString();
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount() && holder[0] == null; i++) {
                collectFirstText(vg.getChildAt(i), holder);
            }
        }
    }

    private static String oneLine(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private static JSONObject obj(String k, Object v) {
        JSONObject o = new JSONObject();
        try {
            o.put(k, v);
        } catch (org.json.JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }
}
