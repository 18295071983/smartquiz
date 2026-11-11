package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Map;

/**
 * RemoteCardView —— 把服务端 {@code /v2/view} 的 {@code cards[]} 渲染成**原生卡片**。
 *
 * <p>它是"网页面板 → 手机原生"的 UI 转换器：输入只有 {@code List<Map>}，输出只有 View，
 * 不碰网络、不碰状态机。三条使用路径共用它：连接页自检、聊天页 REMOTE_CARD item、
 * 审批卡插入。</p>
 *
 * <h3>2026-11-12 修的设计问题</h3>
 * <ul>
 *   <li><b>暗色主题下整张卡消失</b>：底色/描边写死 {@code 0x0A000000}/{@code 0x1A000000}
 *       （4%/10% 黑），暗色底上等于透明。改为读主题 {@code colorSurfaceVariant}/{@code colorOutline}。</li>
 *   <li><b>kind 当标题</b>：没有 title 时直接把 {@code status}/{@code checks} 这种英文
 *       kind 显示给用户。改为中文映射。</li>
 *   <li><b>丢字段</b>：{@code checks[].hint}、卡片级 {@code detail}/{@code error} 全被忽略 ——
 *       自检失败时手机上只剩一个名字，看不到原因（会话读取失败时更是一张空白卡）。</li>
 *   <li><b>时间线不可读</b>：每条消息硬截 3 行且无法展开。改为点一下展开/收起。</li>
 *   <li><b>每 3 秒全量重建</b>：自检轮询每次都 {@code removeAllViews}，正在看的展开态被清掉、
 *       滚动位置跳动。改为内容签名未变直接跳过。</li>
 *   <li><b>审批卡不可点</b>：{@code approval_id} 被丢掉。现在把它挂到行上（tag），
 *       由调用方决定要不要接点击。</li>
 *   <li><b>"呼吸"卡会叠</b>：{@link #appendBreath} 每次追加一张。改为先移除上一张。</li>
 * </ul>
 *
 * 支持的 card.kind：status / session（rows）、checks / connections / approval（items）、
 * timeline（messages）；未知 kind 只要有 rows/items/messages 也照渲染，只是标题走兜底。
 */
public final class RemoteCardView {

    /**
     * 渲染内容签名：内容和上次一致就跳过重建（自检 3 秒一轮，避免闪/丢展开态）。
     * 用无 key 的 tag（容器是专用 ViewGroup，不与其他代码共用），避免 setTag(int) 要求
     * 应用私有 id 资源。
     */
    private static final Object TAG_BREATH = new Object();

    private RemoteCardView() {
    }

    public static void render(ViewGroup container, Map<String, Object> view) {
        if (container == null || view == null) return;
        Context ctx = container.getContext();
        Object cardsObj = view.get("cards");
        boolean hasCards = cardsObj instanceof List && !((List<?>) cardsObj).isEmpty();
        String signature = hasCards
                ? String.valueOf(view.get("header")) + "|" + String.valueOf(cardsObj) : "";
        // 有内容且没变：保留展开态/滚动位置，连空态也不重画
        if (hasCards && signature.equals(container.getTag())) return;
        container.setTag(signature);
        container.removeAllViews();
        container.setVisibility(View.VISIBLE);

        Object header = view.get("header");
        if (header instanceof Map) {
            String title = str(((Map<?, ?>) header).get("title"));
            String badge = str(((Map<?, ?>) header).get("badge"));
            if (!title.isEmpty() || !badge.isEmpty()) {
                TextView tv = new TextView(ctx);
                tv.setText("跟随：" + (title.isEmpty() ? "(未命名会话)" : title)
                        + (badge.isEmpty() ? "" : " · " + badge));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                tv.setTextColor(secondary(ctx));
                tv.setPadding(0, 0, 0, dp(ctx, 6));
                container.addView(tv);
            }
        }

        Object cards = view.get("cards");
        if (!(cards instanceof List) || ((List<?>) cards).isEmpty()) {
            container.addView(empty(ctx, "电脑端没有返回卡片（可点「刷新」重试）"));
            return;
        }
        int rendered = 0;
        for (Object co : (List<?>) cards) {
            if (!(co instanceof Map)) continue;
            View card = buildCard(ctx, (Map<?, ?>) co);
            if (card != null) {
                container.addView(card);
                rendered += 1;
            }
        }
        if (rendered == 0) container.addView(empty(ctx, "暂无内容"));
    }

    /**
     * 追加一张"呼吸"卡（RTT / 在线 / 落后条数）。重复调用只保留最新一张：
     * 旧实现每次刷新都追加，轮询几次后一屏都是"呼吸"。
     */
    public static void appendBreath(ViewGroup container, boolean online, long rttMs, Object gap, String error) {
        if (container == null) return;
        Context ctx = container.getContext();
        String text = online
                ? "在线 · RTT " + rttMs + "ms · 落后 " + (gap == null ? "?" : gap) + " 条"
                : "心跳失败" + (error == null || error.isEmpty() ? "" : "：" + error);
        int color = online ? okColor(ctx) : errColor(ctx);
        // 复用上一张"呼吸"卡：只改文字。旧实现每次追加，轮询几次后一屏都是"呼吸"
        for (int i = container.getChildCount() - 1; i >= 0; i -= 1) {
            View child = container.getChildAt(i);
            if (!TAG_BREATH.equals(child.getTag()) || !(child instanceof LinearLayout)) continue;
            LinearLayout existing = (LinearLayout) child;
            if (existing.getChildCount() > 1 && existing.getChildAt(1) instanceof TextView) {
                TextView tv = (TextView) existing.getChildAt(1);
                tv.setText(text);
                tv.setTextColor(color);
                return;
            }
            container.removeViewAt(i);
        }
        LinearLayout box = cardBox(ctx);
        box.setTag(TAG_BREATH);
        box.addView(cardTitle(ctx, "呼吸"));
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(color);
        box.addView(tv);
        container.addView(box);
    }

    /** 某一行的审批 id（没有则空串）；渲染器只负责挂上，交互由页面决定 */
    public static String approvalIdOf(View row) {
        if (row == null) return "";
        Object tag = row.getTag();
        return tag == null ? "" : String.valueOf(tag);
    }

    // ---------------- 单张卡片 ----------------

    private static View buildCard(Context ctx, Map<?, ?> card) {
        String kind = str(card.get("kind"));
        LinearLayout box = cardBox(ctx);
        box.addView(cardTitle(ctx, titleOf(kind, str(card.get("title")))));

        // 卡片级说明：会话读取失败/插件降级时，这是唯一的原因来源
        String detail = str(card.get("detail"));
        String error = str(card.get("error"));
        if (!detail.isEmpty() || !error.isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText(error.isEmpty() ? detail : (detail.isEmpty() ? error : detail + "\n" + error));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTextColor(error.isEmpty() ? secondary(ctx) : errColor(ctx));
            tv.setPadding(0, 0, 0, dp(ctx, 4));
            box.addView(tv);
        }

        int rows = renderRows(ctx, box, card.get("rows"));
        int items = renderItems(ctx, box, card.get("items"));
        int messages = renderMessages(ctx, box, card.get("messages"));
        return rows + items + messages > 0 || !detail.isEmpty() || !error.isEmpty() ? box : null;
    }

    private static int renderRows(Context ctx, LinearLayout box, Object rowsObj) {
        if (!(rowsObj instanceof List)) return 0;
        int n = 0;
        for (Object r : (List<?>) rowsObj) {
            if (!(r instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) r;
            box.addView(row(ctx, str(m.get("label")), str(m.get("value"))));
            n += 1;
        }
        return n;
    }

    private static int renderItems(Context ctx, LinearLayout box, Object itemsObj) {
        if (!(itemsObj instanceof List)) return 0;
        int n = 0;
        for (Object it : (List<?>) itemsObj) {
            if (!(it instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) it;
            View line = item(ctx, str(m.get("state")), str(m.get("name")),
                    str(m.get("detail")), str(m.get("hint")));
            String approvalId = str(m.get("approval_id"));
            if (!approvalId.isEmpty()) {
                line.setTag(approvalId);
                line.setClickable(true);
                line.setFocusable(true);
            }
            box.addView(line);
            n += 1;
        }
        return n;
    }

    private static int renderMessages(Context ctx, LinearLayout box, Object messagesObj) {
        if (!(messagesObj instanceof List)) return 0;
        int n = 0;
        for (Object mo : (List<?>) messagesObj) {
            if (!(mo instanceof Map)) continue;
            box.addView(message(ctx, (Map<?, ?>) mo));
            n += 1;
        }
        return n;
    }

    /** 无 title 时的中文兜底 —— 绝不再把 kind 原文漏给用户 */
    private static String titleOf(String kind, String title) {
        if (title != null && !title.trim().isEmpty()) return title;
        switch (kind) {
            case "status": return "电脑端";
            case "checks": return "自检";
            case "connections": return "连接";
            case "session": return "会话";
            case "approval": return "待你处理";
            case "timeline": return "最近消息";
            case "caps": return "能力协商";
            default: return "信息";
        }
    }

    private static View empty(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(secondary(ctx));
        tv.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6));
        return tv;
    }

    private static LinearLayout cardBox(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 14));
        bg.setColor(surface(ctx));
        bg.setStroke(1, outline(ctx));
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

    private static LinearLayout item(Context ctx, String state, String name, String detail, String hint) {
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(ctx, 3), 0, dp(ctx, 3));

        TextView mark = new TextView(ctx);
        mark.setText("ok".equals(state) ? "✓" : ("error".equals(state) ? "✗" : "○"));
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mark.setTextColor("ok".equals(state) ? okColor(ctx)
                : ("error".equals(state) ? errColor(ctx) : warnColor(ctx)));
        mark.setContentDescription("ok".equals(state) ? "正常"
                : ("error".equals(state) ? "异常" : "注意"));
        line.addView(mark);

        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(ctx, 8), 0, 0, 0);

        TextView n = new TextView(ctx);
        n.setText(name);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        n.setTextColor(primary(ctx));
        right.addView(n);

        // detail 是现象，hint 是"怎么办"：旧渲染把 hint 整个丢了，用户只能看到错误看不到救援
        String secondary = detail == null ? "" : detail;
        if (hint != null && !hint.trim().isEmpty()) {
            secondary = secondary.isEmpty() ? ("建议：" + hint) : (secondary + "\n建议：" + hint);
        }
        if (!secondary.isEmpty()) {
            TextView d = new TextView(ctx);
            d.setText(secondary);
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
        line.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4));

        String role = str(m.get("role"));
        boolean user = "user".equals(role);
        String who = user
                ? ("phone".equals(str(m.get("origin"))) ? "你(手机)" : "你(电脑)")
                : "AI";

        TextView chip = new TextView(ctx);
        chip.setText(who);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        chip.setTextColor(user ? accent(ctx) : tertiary(ctx));
        line.addView(chip);

        TextView text = new TextView(ctx);
        text.setText(str(m.get("text")));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        text.setTextColor(primary(ctx));
        // 预览 3 行；点一下展开全文（时间线在手机上常常只有半句话，旧实现永远看不全）
        text.setMaxLines(3);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setClickable(true);
        text.setOnClickListener(v -> {
            boolean collapsed = text.getMaxLines() == 3;
            text.setMaxLines(collapsed ? Integer.MAX_VALUE : 3);
            text.setEllipsize(collapsed ? null : android.text.TextUtils.TruncateAt.END);
            text.invalidate();
        });
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

    private static boolean dark(Context ctx) {
        int mode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int themeColor(Context ctx, int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) return androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId);
            return tv.data;
        }
        return fallback;
    }

    /** 卡片底色：走主题（暗色下不能再用半透明黑） */
    private static int surface(Context ctx) {
        int attr = com.google.android.material.R.attr.colorSurfaceVariant;
        return themeColor(ctx, attr, dark(ctx) ? 0x1FFFFFFF : 0x0A000000);
    }

    private static int outline(Context ctx) {
        int attr = com.google.android.material.R.attr.colorOutline;
        return themeColor(ctx, attr, dark(ctx) ? 0x33FFFFFF : 0x1A000000);
    }

    private static int primary(Context ctx) {
        return themeColor(ctx, android.R.attr.textColorPrimary, dark(ctx) ? 0xFFEEEEEE : 0xFF222222);
    }

    private static int secondary(Context ctx) {
        return themeColor(ctx, android.R.attr.textColorSecondary, dark(ctx) ? 0xFFAAAAAA : 0xFF777777);
    }

    private static int accent(Context ctx) {
        return themeColor(ctx, com.google.android.material.R.attr.colorPrimary, 0xFF1565C0);
    }

    private static int tertiary(Context ctx) {
        return themeColor(ctx, com.google.android.material.R.attr.colorTertiary, 0xFF6A1B9A);
    }

    private static int okColor(Context ctx) {
        return dark(ctx) ? 0xFF81C784 : 0xFF2E7D32;
    }

    private static int errColor(Context ctx) {
        return themeColor(ctx, com.google.android.material.R.attr.colorError, 0xFFC62828);
    }

    private static int warnColor(Context ctx) {
        return dark(ctx) ? 0xFFFFD54F : 0xFFF9A825;
    }
}
