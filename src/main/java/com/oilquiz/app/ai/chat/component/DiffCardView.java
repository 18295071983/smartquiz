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
 * <p>生产者：{@code file_generator} 的 create/write/append（写前快照旧文本，写成功后把
 * {@code before}/{@code after} 放进 {@code additionalInfo.diff}）、edit（同上，另带 unified diff）、
 * diff（只预览不落盘，标题标「变更预览」）。2026-10-28 起它是唯一自动生产者——
 * {@code python_file_ops} 的写侧已删除。模型也可通过 {@code ui_component(create, diff)} 手动建卡。</p>
 *
 * <p>这些载荷只走 UI 通道，不改模型可见的工具结果。</p>
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

        // 2026-10-28：卡片只展开改动附近几行，其余折叠；点卡片/点按钮看完整差异——
        // 与其他工具卡（文件卡点开预览、图表卡点开数据表）保持一致的交互。
        shell.setCardClickable(() -> showDetailDialog(context, p, stats));
        shell.addAction(context.getString(R.string.diff_view_full), false,
                () -> showDetailDialog(context, p, stats));

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

    /** 详情弹窗里最多显示多少行（再长就截断，避免一次塞几千行） */
    private static final int MAX_DETAIL_LINES = 2000;
    /** 详情弹窗的上下文：比卡片的 3 行宽，便于看清整段改动 */
    static final int DETAIL_CONTEXT = 8;

    /**
     * 详情弹窗要显示的行：默认给更宽的上下文（{@link #DETAIL_CONTEXT}），
     * 若载荷本身带 {@code hunks} 就照它显示（已经算好，不重算）。
     */
    static List<SimpleLineDiff.Line> resolveDetailLines(JSONObject p, int contextLines) {
        if (p == null) return new ArrayList<>();
        JSONArray hunks = p.optJSONArray("hunks");
        if (hunks != null && hunks.length() > 0) {
            return resolveLines(p);
        }
        return SimpleLineDiff.hunks(p.optString("before", ""), p.optString("after", ""), contextLines);
    }

    /** 点卡片/点「查看完整差异」：把更宽的差异放进可滚动的弹窗 */
    private static void showDetailDialog(Context context, JSONObject p, int[] stats) {
        try {
            List<SimpleLineDiff.Line> lines = resolveDetailLines(p, DETAIL_CONTEXT);
            int shown = Math.min(lines.size(), MAX_DETAIL_LINES);
            boolean truncated = lines.size() > shown;

            // 逐行着色（2026-10-28 实测反馈：弹窗里全是黑字，"哪几行被删"要靠读减号数）。
            // SpannableStringBuilder 一次 setText 搞定，不用逐行建 View。
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            for (int i = 0; i < shown; i++) {
                SimpleLineDiff.Line line = lines.get(i);
                int start = sb.length();
                sb.append(prefix(line.kind)).append(line.text).append('\n');
                sb.setSpan(new android.text.style.ForegroundColorSpan(color(context, line.kind)),
                        start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (truncated) {
                sb.append(context.getString(R.string.diff_detail_truncated, shown));
            }

            TextView tv = new TextView(context);
            tv.setText(sb);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextSize(ComponentTheme.metaTextSize());
            tv.setTextColor(ComponentTheme.textPrimary(context));
            tv.setTextIsSelectable(true);
            float density = context.getResources().getDisplayMetrics().density;
            int pad = (int) (12 * density);
            tv.setPadding(pad, pad, pad, pad);

            android.widget.ScrollView sv = new android.widget.ScrollView(context);
            sv.addView(tv);

            new androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle(detailTitle(context, p, stats))
                    .setView(sv)
                    .setNegativeButton(R.string.close, null)
                    .show();
        } catch (Throwable t) {
            android.util.Log.w("DiffCardView", "差异详情弹窗失败: " + t.getMessage());
        }
    }

    /**
     * 弹窗标题：只用文件名，别把整句标题塞进标题栏——实测长标题会被系统截成「…」，
     * 连 +a −b 都看不见。
     */
    static String detailTitle(Context context, JSONObject p, int[] stats) {
        String path = p.optString("path", "");
        String name = path;
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash < path.length() - 1) {
            name = path.substring(slash + 1);
        }
        if (name == null || name.trim().isEmpty()) {
            name = p.optString("title", "");
        }
        if (name.trim().isEmpty()) {
            name = context.getString(R.string.card_diff_title);
        }
        if (name.length() > 28) {
            name = name.substring(0, 27) + "…";
        }
        return context.getString(R.string.diff_detail_title, name)
                + String.format(Locale.US, "  +%d −%d", stats[0], stats[1]);
    }

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
            // 2026-10-28：只展开改动 ±3 行，远距离改动之间折叠成「⋯」。
            // 以前是把整篇文件铺成 +/- 两遍，改一行也要滚半天，观感上等于没有 diff。
            return SimpleLineDiff.hunks(p.optString("before", ""), p.optString("after", ""), 3);
        }
        return out;
    }
}
