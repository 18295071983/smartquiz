package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.graphics.Color;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.turn.AssistantTurn.Part;
import com.oilquiz.app.theme.ThemeColors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * {@code thinking} part → WebView 组件的载荷（PoC，2026-11-18）。
 *
 * <p>与原生渲染器共用 {@link ThinkingSummary} 的状态/文案口径，避免两套 UI 各说各话。
 * 输出形状就是 {@code assets/agent_panel/agent_panel.html} 里 {@code window.qzAgent.update(state)} 的入参：</p>
 *
 * <pre>
 * {
 *   "active": true,
 *   "label": "正在核对第 3 处调用点…",
 *   "steps": [
 *     {"id":"r1","kind":"thinking","text":"……"},
 *     {"id":"t1","kind":"tool","title":"grep","detail":"…","status":"running","result":"…"}
 *   ],
 *   "theme": {"fg":"#…","muted":"#…","accent":"#…","border":"#…","surface":"rgba(…)"}
 * }
 * </pre>
 *
 * <p>纯数据、可单测；不引 org.json 之外的东西。</p>
 */
public final class AgentPanelPayload {

    /** 工具输出最多带这么多字：面板是概览，长日志完整版仍在原生详情里看。 */
    private static final int MAX_RESULT_CHARS = 4000;

    private AgentPanelPayload() {
    }

    public static JSONObject of(Context ctx, List<Part> members, boolean dark) {
        JSONObject out = new JSONObject();
        ThinkingSummary st = ThinkingSummary.of(members);
        try {
            out.put("active", st.active);
            out.put("label", st.label(ctx));
            // 收起态第二行（与原生 shell 的副标题同一份文案）
            out.put("summary", st.summary() == null ? "" : st.summary());
            JSONArray steps = new JSONArray();
            if (members != null) {
                for (Part m : members) {
                    if (m == null) {
                        continue;
                    }
                    if (Part.TYPE_REASONING.equals(m.type)) {
                        steps.put(new JSONObject()
                                .put("id", m.id)
                                .put("kind", "thinking")
                                .put("text", m.text == null ? "" : m.text));
                    } else if (Part.TYPE_TOOL_GROUP.equals(m.type) && m.group != null) {
                        for (Part t : m.group) {
                            if (t == null) {
                                continue;
                            }
                            steps.put(toolStep(t));
                        }
                    }
                }
            }
            out.put("steps", steps);
            out.put("theme", theme(ctx, dark));
        } catch (JSONException e) {
            // JSONObject 不会因为 put 字符串失败；真失败就返回最小载荷，面板显示空态即可
            try {
                out.put("active", false);
                out.put("label", "深度思考");
                out.put("steps", new JSONArray());
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    private static JSONObject toolStep(Part t) throws JSONException {
        String status = ToolCallPartRenderer.ToolCardProps.normalizeStatus(t.status);
        String name = t.toolName == null ? "工具" : t.toolName.trim();
        String shown;
        try {
            String display = com.oilquiz.app.ai.chat.ToolMetaRegistry.displayName(name);
            shown = display == null || display.isEmpty() ? name : display;
        } catch (Throwable th) {
            shown = name;
        }
        String detail = ToolActivityRowView.argsPreview(t.args);
        String result = t.result == null ? "" : t.result.trim();
        if (result.length() > MAX_RESULT_CHARS) {
            result = result.substring(0, MAX_RESULT_CHARS) + "\n…（已截断）";
        }
        return new JSONObject()
                .put("id", t.id)
                .put("kind", "tool")
                .put("title", shown)
                .put("detail", detail)
                .put("status", status)
                .put("result", result);
    }

    /** 主题变量：面板只吃 CSS 变量，配色仍由 App 主题决定（深/浅、换肤都跟随）。 */
    public static JSONObject theme(Context ctx, boolean dark) throws JSONException {
        int fg = ThemeColors.get(ctx, R.color.chat_v2_text);
        int muted = ThemeColors.get(ctx, R.color.chat_v2_text_2);
        int accent = ThemeColors.attr(ctx, R.attr.colorPrimary);
        int border = ThemeColors.attr(ctx, R.attr.colorBorder);
        JSONObject o = new JSONObject();
        o.put("fg", hex(fg));
        o.put("muted", hex(muted));
        o.put("accent", hex(accent));
        o.put("border", hex(border));
        o.put("surface", rgba(accent, 0.06f));
        o.put("surface2", hex(ThemeColors.attr(ctx, R.attr.colorControlBackground)));
        o.put("bg", "transparent");
        return o;
    }

    private static String hex(int color) {
        return String.format(java.util.Locale.US, "#%06X", 0xFFFFFF & color);
    }

    private static String rgba(int color, float alpha) {
        return String.format(java.util.Locale.US, "rgba(%d,%d,%d,%.2f)",
                Color.red(color), Color.green(color), Color.blue(color), alpha);
    }
}
