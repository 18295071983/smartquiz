package com.oilquiz.app.ai.remote.view;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 通用视图渲染器（C1，2026-11-14）。
 *
 * <p><b>为什么要有：</b>以前每种卡片都是手机里的一个 Java 类，加一种卡就得发 APK。
 * 这个渲染器只认"组件类型"，不认业务：插件推一棵 JSON 组件树，手机画出来。
 * 以后加界面只改插件（见《远程插件更新指南》）。</p>
 *
 * <p><b>第一批组件（10 个）：</b>{@code stack / row / text / callout / steps / list /
 * actions / options / confirm / keyValue}。</p>
 *
 * <p><b>预算</b>（超限降级，不整块崩）：组件 ≤120、层级 ≤8、list ≤50 行、actions ≤4 个。
 * 未知组件画灰色占位"这部分无法显示"，其余照画。</p>
 *
 * <p><b>线程</b>：只在主线程调用（它直接创建 View）。</p>
 */
public final class ViewRenderer {

    /** 组件树预算 */
    public static final int MAX_NODES = 120;
    public static final int MAX_DEPTH = 8;
    public static final int MAX_LIST_ROWS = 50;
    public static final int MAX_ACTIONS = 4;

    /** 点击回调：action id + args（由上层决定发 invoke 还是本地执行） */
    public interface ActionSink {
        void onAction(String action, JSONObject args);
    }

    private ViewRenderer() {
    }

    /** 渲染一棵组件树；根必须是 stack（也可以是其它单组件）。 */
    public static View render(Context ctx, JSONObject node, ActionSink sink) {
        int[] budget = {MAX_NODES};
        return renderNode(ctx, node, 0, budget, sink);
    }

    private static View renderNode(Context ctx, JSONObject node, int depth, int[] budget, ActionSink sink) {
        if (node == null) return placeholder(ctx);
        if (depth > MAX_DEPTH) return placeholder(ctx);
        if (--budget[0] < 0) return placeholder(ctx);
        String type = node.optString("type", "");
        try {
            switch (type) {
                case "stack":
                    return stack(ctx, node, depth, budget, sink, LinearLayout.VERTICAL);
                case "row":
                    return stack(ctx, node, depth, budget, sink, LinearLayout.HORIZONTAL);
                case "text":
                    return text(ctx, node);
                case "callout":
                    return callout(ctx, node);
                case "steps":
                    return steps(ctx, node);
                case "list":
                    return list(ctx, node, depth, budget, sink);
                case "actions":
                    return actions(ctx, node, sink);
                case "options":
                    return options(ctx, node, sink);
                case "confirm":
                    return confirm(ctx, node, sink);
                case "keyValue":
                    return keyValue(ctx, node);
                default:
                    return placeholder(ctx);
            }
        } catch (Throwable t) {
            return placeholder(ctx);
        }
    }

    // ---------------- 容器 ----------------

    private static View stack(Context ctx, JSONObject node, int depth, int[] budget, ActionSink sink,
                              int orientation) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(orientation);
        if (orientation == LinearLayout.HORIZONTAL) box.setGravity(Gravity.CENTER_VERTICAL);
        JSONArray children = node.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i += 1) {
                JSONObject child = children.optJSONObject(i);
                View v = renderNode(ctx, child, depth + 1, budget, sink);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (orientation == LinearLayout.VERTICAL) lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.bottomMargin = dp(ctx, 6);
                box.addView(v, lp);
            }
        }
        return box;
    }

    // ---------------- 文字类 ----------------

    private static View text(Context ctx, JSONObject node) {
        TextView tv = new TextView(ctx);
        tv.setText(node.optString("text", ""));
        tv.setTextSize(14);
        tv.setTextColor("muted".equals(node.optString("tone")) ? 0xFF8A8A8A : 0xFF1F1F1F);
        tv.setLineSpacing(dp(ctx, 2), 1f);
        return tv;
    }

    private static View callout(Context ctx, JSONObject node) {
        String tone = node.optString("tone", "info");
        int accent;
        int bg;
        switch (tone) {
            case "good":
                accent = 0xFF2E7D32; bg = 0x1A2E7D32; break;
            case "warn":
                accent = 0xFFE65100; bg = 0x1AE65100; break;
            case "bad":
                accent = 0xFFC62828; bg = 0x1AC62828; break;
            default:
                accent = 0xFF2F5BD6; bg = 0x142F5BD6; break;
        }
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(ctx, 10));
        g.setColor(bg);
        box.setBackground(g);
        View bar = new View(ctx);
        GradientDrawable bgBar = new GradientDrawable();
        bgBar.setCornerRadius(dp(ctx, 2));
        bgBar.setColor(accent);
        bar.setBackground(bgBar);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(ctx, 3),
                ViewGroup.LayoutParams.MATCH_PARENT);
        blp.rightMargin = dp(ctx, 9);
        box.addView(bar, blp);
        TextView tv = new TextView(ctx);
        tv.setText(node.optString("text", ""));
        tv.setTextSize(13.5f);
        tv.setTextColor(accent);
        box.addView(tv);
        return box;
    }

    private static View steps(Context ctx, JSONObject node) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        JSONArray items = node.optJSONArray("items");
        if (items == null) return empty(ctx);
        for (int i = 0; i < Math.min(items.length(), 12); i += 1) {
            Object raw = items.opt(i);
            String label;
            String status = "todo";
            String detail = "";
            if (raw instanceof JSONObject) {
                JSONObject o = (JSONObject) raw;
                label = o.optString("label", "");
                status = o.optString("status", "todo");
                detail = o.optString("detail", "");
            } else {
                label = String.valueOf(raw);
            }
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView mark = new TextView(ctx);
            switch (status) {
                case "done": mark.setText("✓"); mark.setTextColor(0xFF2E7D32); break;
                case "current": mark.setText("●"); mark.setTextColor(0xFF2F5BD6); break;
                case "failed": mark.setText("✕"); mark.setTextColor(0xFFC62828); break;
                default: mark.setText("○"); mark.setTextColor(0xFFB0B0B0); break;
            }
            mark.setTextSize(13);
            mark.setPadding(0, 0, dp(ctx, 8), 0);
            row.addView(mark);
            TextView tv = new TextView(ctx);
            tv.setText(TextUtils.isEmpty(detail) ? label : (label + "  " + detail));
            tv.setTextSize(13.5f);
            tv.setTextColor("todo".equals(status) ? 0xFF8A8A8A : 0xFF1F1F1F);
            row.addView(tv);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(ctx, 4);
            box.addView(row, lp);
        }
        return box;
    }

    private static View keyValue(Context ctx, JSONObject node) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        JSONArray items = node.optJSONArray("items");
        if (items == null) return empty(ctx);
        for (int i = 0; i < Math.min(items.length(), 24); i += 1) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView k = new TextView(ctx);
            k.setText(o.optString("label", ""));
            k.setTextSize(13);
            k.setTextColor(0xFF8A8A8A);
            row.addView(k, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView v = new TextView(ctx);
            v.setText(o.optString("value", ""));
            v.setTextSize(13);
            v.setTextColor(0xFF1F1F1F);
            row.addView(v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(ctx, 4);
            box.addView(row, lp);
        }
        return box;
    }

    // ---------------- 可交互 ----------------

    private static View list(Context ctx, JSONObject node, int depth, int[] budget, ActionSink sink) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        JSONArray items = node.optJSONArray("items");
        if (items == null) return empty(ctx);
        int n = Math.min(items.length(), MAX_LIST_ROWS);
        for (int i = 0; i < n; i += 1) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            box.addView(listRow(ctx, o, sink));
        }
        if (items.length() > n) {
            TextView more = new TextView(ctx);
            more.setText("还有 " + (items.length() - n) + " 项");
            more.setTextSize(12.5f);
            more.setTextColor(0xFF8A8A8A);
            more.setPadding(dp(ctx, 4), dp(ctx, 6), 0, 0);
            box.addView(more);
        }
        return box;
    }

    private static View listRow(Context ctx, JSONObject o, ActionSink sink) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 10));
        String tone = o.optString("tone", "");
        bg.setColor("bad".equals(tone) ? 0x1AC62828 : ("warn".equals(tone) ? 0x1AE65100 : 0x14000000));
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 6);
        row.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(ctx);
        title.setText(o.optString("title", ""));
        title.setTextSize(14);
        title.setTextColor(0xFF1F1F1F);
        col.addView(title);
        String sub = o.optString("subtitle", "");
        if (!sub.isEmpty()) {
            TextView s = new TextView(ctx);
            s.setText(sub);
            s.setTextSize(12);
            s.setTextColor(0xFF7A7A7A);
            s.setPadding(0, dp(ctx, 2), 0, 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String trailing = o.optString("trailing", "");
        if (!trailing.isEmpty()) {
            TextView t = new TextView(ctx);
            t.setText(trailing);
            t.setTextSize(12.5f);
            t.setTextColor(0xFF8A8A8A);
            row.addView(t);
        }
        JSONObject action = o.optJSONObject("action");
        if (action != null) {
            TextView arrow = new TextView(ctx);
            arrow.setText("›");
            arrow.setTextSize(16);
            arrow.setTextColor(0xFF8A8A8A);
            arrow.setPadding(dp(ctx, 6), 0, 0, 0);
            row.addView(arrow);
            row.setOnClickListener(v -> dispatch(sink, action));
        }
        return row;
    }

    private static View actions(Context ctx, JSONObject node, ActionSink sink) {
        JSONArray items = node.optJSONArray("items");
        if (items == null) return empty(ctx);
        int n = Math.min(items.length(), MAX_ACTIONS);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(n <= 2 ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (int i = 0; i < n; i += 1) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            boolean primary = "primary".equals(o.optString("style", ""));
            TextView btn = new TextView(ctx);
            btn.setText(o.optString("label", ""));
            btn.setTextSize(13.5f);
            btn.setGravity(Gravity.CENTER);
            btn.setTypeface(null, primary ? Typeface.BOLD : Typeface.NORMAL);
            btn.setTextColor(primary ? 0xFFFFFFFF : 0xFF2F5BD6);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(ctx, 10));
            bg.setColor(primary ? 0xFF2F5BD6 : 0x142F5BD6);
            btn.setBackground(bg);
            btn.setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 9));
            JSONObject action = o.optJSONObject("action");
            if (action != null) btn.setOnClickListener(v -> dispatch(sink, action));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (row.getOrientation() == LinearLayout.HORIZONTAL) {
                lp.weight = 1f;
                lp.width = 0;
            } else {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            }
            lp.rightMargin = dp(ctx, 8);
            lp.bottomMargin = dp(ctx, 6);
            row.addView(btn, lp);
        }
        return row;
    }

    private static View options(Context ctx, JSONObject node, ActionSink sink) {
        JSONArray arr = node.optJSONArray("options");
        if (arr == null) return empty(ctx);
        String id = node.optString("id", "option");
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < Math.min(arr.length(), 8); i += 1) {
            Object raw = arr.opt(i);
            String label = raw instanceof JSONObject
                    ? ((JSONObject) raw).optString("label", "") : String.valueOf(raw);
            TextView tv = new TextView(ctx);
            tv.setText(label);
            tv.setTextSize(13.5f);
            tv.setTextColor(0xFF2F5BD6);
            tv.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(ctx, 10));
            bg.setColor(0x142F5BD6);
            tv.setBackground(bg);
            final String value = label;
            tv.setOnClickListener(v -> {
                JSONObject args = new JSONObject();
                try { args.put("value", value); } catch (Throwable ignored) { /* 忽略 */ }
                if (sink != null) sink.onAction(id, args);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(ctx, 6);
            box.addView(tv, lp);
        }
        return box;
    }

    private static View confirm(Context ctx, JSONObject node, ActionSink sink) {
        JSONArray two = new JSONArray();
        JSONObject ok = new JSONObject();
        try {
            ok.put("label", node.optString("confirmLabel", "确定"));
            ok.put("style", "primary");
            JSONObject a = new JSONObject();
            a.put("id", node.optString("id", "confirm"));
            a.put("args", new JSONObject().put("confirmed", true));
            ok.put("action", a);
        } catch (Throwable ignored) { /* 忽略 */ }
        JSONObject no = new JSONObject();
        try {
            no.put("label", node.optString("cancelLabel", "取消"));
            JSONObject a = new JSONObject();
            a.put("id", node.optString("id", "confirm"));
            a.put("args", new JSONObject().put("confirmed", false));
            no.put("action", a);
        } catch (Throwable ignored) { /* 忽略 */ }
        two.put(ok);
        two.put(no);
        JSONObject wrap = new JSONObject();
        try { wrap.put("items", two); } catch (Throwable ignored) { /* 忽略 */ }
        return actions(ctx, wrap, sink);
    }

    // ---------------- 工具 ----------------

    private static void dispatch(ActionSink sink, JSONObject action) {
        if (sink == null || action == null) return;
        sink.onAction(action.optString("id", action.optString("action", "")),
                action.optJSONObject("args"));
    }

    private static View placeholder(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setText("这部分无法显示");
        tv.setTextSize(12);
        tv.setTextColor(0xFF9E9E9E);
        tv.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
        return tv;
    }

    private static View empty(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setText("暂无数据");
        tv.setTextSize(12.5f);
        tv.setTextColor(0xFF9E9E9E);
        return tv;
    }

    private static int dp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }
}
