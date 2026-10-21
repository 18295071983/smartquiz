package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JSON 查看器组件：格式化展示工具返回的原始 JSON 数据（调试/原始结果）。
 *
 * <p>数据格式（ComponentData.props）：</p>
 * <pre>
 * {
 *   "title": "原始数据",     // 可选
 *   "data": {...},          // 任意 JSON 对象/数组；**也接受字符串形态的 JSON**（工具常把 JSON 塞成字符串返回）
 *   "maxHeight": 240        // 可选，dp；超长内容滚动上限
 * }
 * </pre>
 *
 * <p>外壳统一走 {@link CardShell}（与 info/table/metric 等内置卡同一套视觉），限高滚动复用
 * {@link CardShell.MaxHeightScrollView}，字号/颜色走 {@link ComponentTheme} token——此前本卡自绘
 * LinearLayout + 背景，是"组件和聊天页不是一套语言"（component-system-review §2 P0）的残留实例。</p>
 */
public class JsonViewerCard implements ChatComponent {

    /** maxHeight 缺省值（dp） */
    private static final int DEFAULT_MAX_HEIGHT_DP = 200;

    @Override
    public String getType() {
        return "json_viewer";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null
                && (data.props.has("data") || data.props.has("json"));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        Object raw = p.has("data") ? p.opt("data") : p.opt("json");
        String jsonText = raw == null ? "" : prettyPrint(raw);

        CardShell shell = CardShell.create(context)
                .setHeader(p.optString("title", ""),
                        TextUtils.isEmpty(jsonText) ? null : lineCount(jsonText) + " 行");
        if (TextUtils.isEmpty(jsonText)) {
            shell.setState(CardShell.State.EMPTY);
            return shell;
        }

        TextView jsonTv = new TextView(context);
        jsonTv.setText(jsonText);
        jsonTv.setTextSize(ComponentTheme.metaTextSize());
        jsonTv.setTextColor(ComponentTheme.textSecondary(context));
        jsonTv.setTypeface(Typeface.MONOSPACE);
        jsonTv.setLineSpacing(0, 1.25f);
        jsonTv.setTextIsSelectable(true);

        // 限高可滚动：maxHeight<=0 时不设上限（内容自适应高度）
        int maxHeightDp = p.optInt("maxHeight", DEFAULT_MAX_HEIGHT_DP);
        CardShell.MaxHeightScrollView scroller = new CardShell.MaxHeightScrollView(context,
                maxHeightDp > 0 ? ComponentTheme.dp(context, maxHeightDp) : 0);
        scroller.setFillViewport(false);
        scroller.addView(jsonTv, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addBody(scroller);
        return shell;
    }

    /**
     * 美化 JSON 文本（容错）。
     *
     * <p>{@code data} 常以<b>字符串</b>形态进来（工具结果把 JSON 序列化后当文本返回、或模型直接传字符串），
     * 此前只有 {@link JSONObject}/{@link JSONArray} 会被格式化，字符串走 {@code String.valueOf} 原样吐——
     * 结果是一整行不换行的转义文本，看起来像"卡渲染失败贴了段原文"。现在字符串形态的 JSON 也解析后美化，
     * 解析不了（真·纯文本/数字/布尔）才原样显示。</p>
     */
    private static String prettyPrint(Object raw) {
        if (raw instanceof JSONObject) {
            return indent((JSONObject) raw);
        }
        if (raw instanceof JSONArray) {
            return indent((JSONArray) raw);
        }
        String s = String.valueOf(raw).trim();
        if (looksLikeJson(s)) {
            try {
                if (s.charAt(0) == '{') {
                    return indent(new JSONObject(s));
                }
                return indent(new JSONArray(s));
            } catch (org.json.JSONException ignored) {
                // 看着像 JSON 但解析失败：按原文显示，不吞内容
            }
        }
        return s;
    }

    /** 两空格缩进；toString(2) 抛异常时退回紧凑格式，保证不渲染空白卡 */
    private static String indent(JSONObject o) {
        try {
            return o.toString(2);
        } catch (org.json.JSONException e) {
            return o.toString();
        }
    }

    private static String indent(JSONArray a) {
        try {
            return a.toString(2);
        } catch (org.json.JSONException e) {
            return a.toString();
        }
    }

    /** 首尾花括号/方括号且长度>1 才当 JSON 试解析，避免把普通句子送去解析 */
    private static boolean looksLikeJson(String s) {
        if (s.length() < 2) {
            return false;
        }
        char a = s.charAt(0);
        char z = s.charAt(s.length() - 1);
        return (a == '{' && z == '}') || (a == '[' && z == ']');
    }

    /** 行数（表头 meta 用），按换行符数计 */
    private static int lineCount(String text) {
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
