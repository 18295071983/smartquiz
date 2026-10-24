package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.text.TextUtils;
import android.widget.TextView;

import com.oilquiz.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 变更对比卡（diff，2026-10-23；2026-10-24 接通自动生产者）。
 *
 * <p>生产者：{@code file_generator} 的覆盖写/追加、{@code python_file_ops} 的
 * write/append/replace 在写前快照旧文本，写成功后把 {@code before}/{@code after}
 * 放进 {@code additionalInfo.diff}（只走 UI 通道，不改模型可见结果）；
 * 模型也可通过 {@code ui_component(create, diff)} 手动建卡。</p>
 *
 * <p>两种输入形态，任给其一：
 * <ol>
 *   <li>{@code before}/{@code after}：两段完整文本，渲染端本地算行级 diff
 *       （{@link SimpleLineDiff}，超过 400 行退化为整段替换）；</li>
 *   <li>{@code hunks}：已经算好的行，{@code [{type:"ctx|add|del", text:"..."}]}。</li>
 * </ol>
 *
 * <p>数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "变更对比（可选）",
 *   "path": "src/.../Foo.java（可选，副标题）",
 *   "before": "旧内容", "after": "新内容",
 *   // 或 "hunks": [{"type":"add","text":"+ 新增行"}]
 * }
 * </pre>
 */
public class DiffCardView implements ChatComponent {

    private static final int MAX_DISPLAY_LINES = 400;

    @Override
    public String getType() {
        return "diff";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && !resolveLines(data.props).isEmpty();
    }

    @Override
    public android.view.View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String title = p.optString("title", "");
        String path = p.optString("path", "");
        List<SimpleLineDiff.Line> lines = resolveLines(p);
        int[] stats = SimpleLineDiff.stats(lines);

        CardShell shell = CardShell.create(context)
                .setHeaderIcon(R.drawable.ic_code)
                .setHeader(TextUtils.isEmpty(title)
                                ? context.getString(R.string.card_diff_title) : title,
                        String.format(Locale.US, "+%d −%d", stats[0], stats[1]))
                .setSubtitle(TextUtils.isEmpty(path) ? null : path);

        if (stats[0] == 0 && stats[1] == 0) {
            shell.setState(CardShell.State.EMPTY, context.getString(R.string.card_diff_empty));
            return shell;
        }

        int shown = Math.min(lines.size(), MAX_DISPLAY_LINES);
        android.widget.LinearLayout body = new android.widget.LinearLayout(context);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);
        for (int i = 0; i < shown; i++) {
            SimpleLineDiff.Line line = lines.get(i);
            TextView tv = new TextView(context);
            tv.setText(prefix(line.kind) + line.text);
            tv.setTextSize(ComponentTheme.metaTextSize());
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextColor(color(context, line.kind));
            tv.setSingleLine(false);
            body.addView(tv);
        }
        shell.addBody(body);
        if (lines.size() > shown) {
            shell.setState(CardShell.State.TRUNCATED,
                    context.getString(R.string.file_list_truncated, lines.size(), shown));
        } else if (shown > 20) {
            shell.enableBodyScroll();
        }
        return shell;
    }

    private static String prefix(SimpleLineDiff.Kind kind) {
        switch (kind) {
            case ADD: return "+ ";
            case DEL: return "− ";
            default: return "  ";
        }
    }

    private static int color(Context context, SimpleLineDiff.Kind kind) {
        switch (kind) {
            case ADD: return ComponentTheme.success(context);
            case DEL: return ComponentTheme.danger(context);
            default: return ComponentTheme.textSecondary(context);
        }
    }

    // ---------------------------------------------------------------- 数据（纯函数，可单测）

    /** hunks 优先；否则用 before/after 本地算 */
    static List<SimpleLineDiff.Line> resolveLines(JSONObject p) {
        List<SimpleLineDiff.Line> out = new ArrayList<>();
        if (p == null) return out;
        JSONArray hunks = p.optJSONArray("hunks");
        if (hunks != null && hunks.length() > 0) {
            for (int i = 0; i < hunks.length(); i++) {
                JSONObject h = hunks.optJSONObject(i);
                if (h == null) continue;
                String type = h.optString("type", "ctx");
                SimpleLineDiff.Kind kind = "add".equals(type) || "+".equals(type)
                        ? SimpleLineDiff.Kind.ADD
                        : ("del".equals(type) || "remove".equals(type) || "-".equals(type)
                        ? SimpleLineDiff.Kind.DEL : SimpleLineDiff.Kind.CONTEXT);
                out.add(new SimpleLineDiff.Line(kind, h.optString("text", "")));
            }
            return out;
        }
        if (p.has("before") || p.has("after")) {
            return SimpleLineDiff.diff(p.optString("before", ""), p.optString("after", ""));
        }
        return out;
    }
}
