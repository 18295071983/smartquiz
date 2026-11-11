package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Map;

/**
 * RemoteCardView —— 把服务端 `/v2/view` 的 cards[] 渲染成**原生卡片组件**。
 *
 * 设计语言对齐电脑面板：圆角浅卡片 + 小号灰标签 + 加粗值 + 状态色（✓ 绿 / ✗ 红 / ○ 黄）。
 * 之前手机上是用等宽 TextView 把 cards 拼成纯文本（妥协方案），这个类取代它。
 *
 * 支持的 card.kind：
 *   status / session  → rows[]   （label + value 两列）
 *   checks / connections / approval → items[] （state + name + detail）
 *   timeline          → messages[] （role + origin + text）
 */
public final class RemoteCardView {

    private RemoteCardView() {
    }

    public static void render(ViewGroup container, Map<String, Object> view) {
        if (container == null || view == null) return;
        Context ctx = container.getContext();
        container.removeAllViews();
        container.setVisibility(android.view.View.VISIBLE);

        Object header = view.get("header");
        if (header instanceof Map) {
            Object title = ((Map<?, ?>) header).get("title");
            if (title != null && !String.valueOf(title).trim().isEmpty()) {
                TextView tv = new TextView(ctx);
                tv.setText("跟随：" + title);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                tv.setTextColor(secondary(ctx));
                tv.setPadding(0, 0, 0, dp(ctx, 6));
                container.addView(tv);
            }
        }

        Object cards = view.get("cards");
        if (!(cards instanceof List)) return;
        for (Object co : (List<?>) cards) {
            if (!(co instanceof Map)) continue;
            Map<?, ?> card = (Map<?, ?>) co;
            container.addView(buildCard(ctx, card));
        }
    }

    /** 追加一张"呼吸"卡（RTT / 在线 / 落后条数），由界面在拿到 pulse 后调用 */
    public static void appendBreath(ViewGroup container, boolean online, long rttMs, Object gap, String error) {
        if (container == null) return;
        Context ctx = container.getContext();
        LinearLayout box = cardBox(ctx);
        box.addView(cardTitle(ctx, "呼吸"));
        String text = online
                ? "✓ 在线 · RTT " + rttMs + "ms · 落后 " + (gap == null ? "?" : gap) + " 条"
                : "✗ 心跳失败" + (error == null || error.isEmpty() ? "" : "：" + error);
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(online ? okColor() : errColor());
        box.addView(tv);
        container.addView(box);
    }

    // ---------------- 单张卡片 ----------------

    private static LinearLayout buildCard(Context ctx, Map<?, ?> card) {
        LinearLayout box = cardBox(ctx);
        Object title = card.get("title");
        box.addView(cardTitle(ctx, title == null ? String.valueOf(card.get("kind")) : String.valueOf(title)));

        Object rows = card.get("rows");
        if (rows instanceof List) {
            for (Object r : (List<?>) rows) {
                if (!(r instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) r;
                box.addView(row(ctx, str(m.get("label")), str(m.get("value"))));
            }
        }

        Object items = card.get("items");
        if (items instanceof List) {
            for (Object it : (List<?>) items) {
                if (!(it instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) it;
                box.addView(item(ctx, str(m.get("state")), str(m.get("name")), str(m.get("detail"))));
            }
        }

        Object messages = card.get("messages");
        if (messages instanceof List) {
            for (Object mo : (List<?>) messages) {
                if (!(mo instanceof Map)) continue;
                box.addView(message(ctx, (Map<?, ?>) mo));
            }
        }
        return box;
    }

    private static LinearLayout cardBox(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 14));
        bg.setColor(0x0A000000);
        bg.setStroke(1, 0x1A000000);
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 8);
        box.setLayoutParams(lp);
        return box;
    }

    private static TextView cardTitle(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(secondary(ctx));
        tv.setAllCaps(false);
        tv.setPadding(0, 0, 0, dp(ctx, 4));
        return tv;
    }

    private static LinearLayout row(Context ctx, String label, String value) {
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));

        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        l.setTextColor(secondary(ctx));
        l.setSingleLine(true);
        line.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setTextColor(primary(ctx));
        v.setGravity(Gravity.END);
        line.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        return line;
    }

    private static LinearLayout item(Context ctx, String state, String name, String detail) {
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(ctx, 3), 0, dp(ctx, 3));

        TextView mark = new TextView(ctx);
        mark.setText("ok".equals(state) ? "✓" : ("error".equals(state) ? "✗" : "○"));
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mark.setTextColor("ok".equals(state) ? okColor() : ("error".equals(state) ? errColor() : warnColor()));
        line.addView(mark);

        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(ctx, 8), 0, 0, 0);

        TextView n = new TextView(ctx);
        n.setText(name);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        n.setTextColor(primary(ctx));
        right.addView(n);

        if (detail != null && !detail.isEmpty()) {
            TextView d = new TextView(ctx);
            d.setText(detail);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            d.setTextColor(secondary(ctx));
            right.addView(d);
        }
        line.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return line;
    }

    private static LinearLayout message(Context ctx, Map<?, ?> m) {
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));

        String role = str(m.get("role"));
        boolean user = "user".equals(role);
        String who = user
                ? ("phone".equals(str(m.get("origin"))) ? "你(手机)" : "你(电脑)")
                : "AI";

        TextView chip = new TextView(ctx);
        chip.setText(who);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        chip.setTextColor(user ? 0xFF1565C0 : 0xFF6A1B9A);
        line.addView(chip);

        TextView text = new TextView(ctx);
        text.setText(str(m.get("text")));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        text.setTextColor(primary(ctx));
        text.setMaxLines(3);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(ctx, 8);
        line.addView(text, lp);
        return line;
    }

    // ---------------- 工具 ----------------

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int dp(Context ctx, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    private static int themeColor(Context ctx, int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) return androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId);
            return tv.data;
        }
        return fallback;
    }

    private static int primary(Context ctx) {
        return themeColor(ctx, android.R.attr.textColorPrimary, 0xFF222222);
    }

    private static int secondary(Context ctx) {
        return themeColor(ctx, android.R.attr.textColorSecondary, 0xFF777777);
    }

    private static int okColor() {
        return 0xFF2E7D32;
    }

    private static int errColor() {
        return 0xFFC62828;
    }

    private static int warnColor() {
        return 0xFFF9A825;
    }
}
