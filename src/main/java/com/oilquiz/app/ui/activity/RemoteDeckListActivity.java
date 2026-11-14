package com.oilquiz.app.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.status.DeckRowModel;
import com.oilquiz.app.ai.chat.status.DockSlotBus;
import com.oilquiz.app.ai.chat.status.SlotAction;
import com.oilquiz.app.ai.chat.status.SlotKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 甲板全屏列表（2026-11-14）。
 *
 * <p>用户口径：「不点击精简，点击后有全屏列表」——甲板本身保持精简（最多几行、不内联展开），
 * 点任意一行就进这里看全部插槽。对应 dsh-Go 的 {@code list} 组件与 Qoder 的"任务列表"形态：
 * 行多的时候不是把输入框顶走，而是换一页看。</p>
 *
 * <p>点行不在这里执行动作：把动作记到 {@link #pendingAction}，关掉自己，由聊天页在
 * {@code onResume} 里用**同一套**分发逻辑处理（避免两处分发逻辑漂移）。</p>
 */
public class RemoteDeckListActivity extends Activity {

    /** 待执行动作：聊天页 onResume 消费一次 */
    public static volatile String pendingAction = null;
    /** ④（2026-11-15）：行内按钮点击的 {slotId, actionId}，同样由聊天页 onResume 消费一次 */
    public static volatile String pendingInlineSlotId = null;
    public static volatile String pendingInlineActionId = null;

    public static void open(Context ctx) {
        Intent it = new Intent(ctx, RemoteDeckListActivity.class);
        if (!(ctx instanceof Activity)) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(it);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF7F7FA);
        int pad = dp(16);
        root.setPadding(pad, dp(18), pad, dp(18));

        // 标题栏：返回 + 标题 + 条数
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextSize(16);
        back.setTextColor(0xFF2F5BD6);
        back.setPadding(0, dp(6), dp(12), dp(6));
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        List<DockSlotBus.Slot> slots = new ArrayList<>(DockSlotBus.list());
        Collections.sort(slots, new Comparator<DockSlotBus.Slot>() {
            @Override
            public int compare(DockSlotBus.Slot a, DockSlotBus.Slot b) {
                int p = Integer.compare(a.priority, b.priority);
                return p != 0 ? p : a.id.compareTo(b.id);
            }
        });

        TextView title = new TextView(this);
        title.setText("电脑状态");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF1A1A1A);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView count = new TextView(this);
        count.setText(slots.size() + " 项");
        count.setTextSize(13);
        count.setTextColor(0xFF8A8A8A);
        bar.addView(count);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(10), 0, 0);

        if (slots.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("电脑那边现在没有要显示的状态");
            empty.setTextSize(14);
            empty.setTextColor(0xFF8A8A8A);
            empty.setPadding(dp(4), dp(20), dp(4), dp(20));
            list.addView(empty);
        } else {
            for (DockSlotBus.Slot s : slots) list.addView(row(s));
        }
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        setTitle("电脑状态");
    }

    /**
     * 一行：圆角底 + 标题/细节 + 箭头（有动作才可点）。
     *
     * <p>④（2026-11-15）：同一份 {@link DeckRowModel} 决定 kind 形态 —— progress 在副标题下
     * 补细进度条 + 百分比，list 右侧「N 条」徽标，action 右侧 1–2 个胶囊按钮。按钮点击记
     * {@code {slotId, actionId}} 后关页，聊天页用既有分发执行；**不会**再打开一层列表。</p>
     */
    private View row(final DockSlotBus.Slot s) {
        DeckRowModel model = DeckRowModel.of(s);
        boolean clickable = s.action != null && !s.action.isEmpty();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(model.warn ? 0x2EE65100 : 0x14000000);
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        if (!SlotKind.PROGRESS.equals(model.kind)) {
            View dot = new View(this);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(model.warn ? 0xFFE65100 : 0xFF9E9E9E);
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
            dotLp.rightMargin = dp(10);
            row.addView(dot, dotLp);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(s.title);
        t.setTextSize(15);
        t.setTextColor(model.warn ? 0xFFB23C00 : 0xFF1F1F1F);
        if (model.warn) t.setTypeface(null, Typeface.BOLD);
        col.addView(t);
        if (s.detail != null && !s.detail.isEmpty()) {
            TextView d = new TextView(this);
            d.setText(s.detail);
            d.setTextSize(12.5f);
            d.setTextColor(0xFF7A7A7A);
            d.setPadding(0, dp(3), 0, 0);
            col.addView(d);
        }
        if (SlotKind.PROGRESS.equals(model.kind)) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, dp(4), 0, 0);
            line.addView(deckProgressBar(model.progress, model.warn));
            TextView pct = new TextView(this);
            pct.setText(model.progress + "%");
            pct.setTextSize(12f);
            pct.setTextColor(model.warn ? 0xFFB23C00 : 0xFF6B6B6B);
            LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pctLp.leftMargin = dp(6);
            line.addView(pct, pctLp);
            col.addView(line);
        }
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (SlotKind.LIST.equals(model.kind)) {
            row.addView(deckCountBadge(model.count));
        } else if (SlotKind.ACTION.equals(model.kind)) {
            LinearLayout buttons = deckInlineActions(s);
            if (buttons != null) row.addView(buttons);
        }

        if (clickable) {
            TextView arrow = new TextView(this);
            arrow.setText("›");
            arrow.setTextSize(18);
            arrow.setTextColor(0xFF8A8A8A);
            row.addView(arrow);
            row.setOnClickListener(v -> {
                pendingInlineSlotId = null;
                pendingInlineActionId = null;
                pendingAction = s.action;
                finish();
            });
        }
        return row;
    }

    /** ④：全屏列表里的细进度条（高 4dp；与甲板同一套"轨道 + 截断填充"）。 */
    private View deckProgressBar(int progress, boolean warn) {
        int width = dp(150);
        int height = Math.max(2, dp(4));
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        View track = new View(this);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(height / 2f);
        trackBg.setColor(0x1F000000);
        track.setBackground(trackBg);
        box.addView(track, new android.widget.FrameLayout.LayoutParams(width, height));
        int fillWidth = Math.round(width * (Math.max(0, Math.min(100, progress)) / 100f));
        if (fillWidth > 0) {
            View fill = new View(this);
            GradientDrawable fillBg = new GradientDrawable();
            fillBg.setCornerRadius(height / 2f);
            fillBg.setColor(warn ? 0xFFE65100 : 0xFF2F5BD6);
            fill.setBackground(fillBg);
            box.addView(fill, new android.widget.FrameLayout.LayoutParams(fillWidth, height));
        }
        box.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        return box;
    }

    /** ④：全屏列表里的「N 条」徽标。 */
    private View deckCountBadge(int count) {
        TextView badge = new TextView(this);
        badge.setText(Math.max(0, count) + " 条");
        badge.setTextSize(12f);
        badge.setTextColor(0xFF2F5BD6);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(9), dp(3), dp(9), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(11));
        bg.setColor(0x142F5BD6);
        badge.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        badge.setLayoutParams(lp);
        return badge;
    }

    /** ④：全屏列表里的 1–2 个行内胶囊按钮；每个按钮自己消费点击。 */
    private LinearLayout deckInlineActions(final DockSlotBus.Slot s) {
        if (s == null || s.actions == null || s.actions.isEmpty()) return null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int shown = 0;
        for (final SlotAction a : s.actions) {
            if (a == null || !a.isValid() || shown >= SlotAction.MAX_ACTIONS) continue;
            TextView btn = new TextView(this);
            btn.setText(a.label);
            btn.setTextSize(12f);
            btn.setMaxLines(1);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(10), dp(4), dp(10), dp(4));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(a.isPrimary() ? 0xFF2F5BD6 : 0x142F5BD6);
            btn.setBackground(bg);
            btn.setTextColor(a.isPrimary() ? 0xFFFFFFFF : 0xFF2F5BD6);
            btn.setClickable(true);
            btn.setOnClickListener(v -> {
                pendingAction = null;
                pendingInlineSlotId = s.id;
                pendingInlineActionId = a.id;
                finish();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(shown == 0 ? 8 : 5);
            box.addView(btn, lp);
            shown++;
        }
        return shown == 0 ? null : box;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
