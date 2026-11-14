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

import com.oilquiz.app.ai.chat.status.DockSlotBus;

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

    /** 一行：圆角底 + 标题/细节 + 箭头（有动作才可点） */
    private View row(DockSlotBus.Slot s) {
        boolean clickable = s.action != null && !s.action.isEmpty();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(s.warn ? 0x2EE65100 : 0x14000000);
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(s.warn ? 0xFFE65100 : 0xFF9E9E9E);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotLp.rightMargin = dp(10);
        row.addView(dot, dotLp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(s.title);
        t.setTextSize(15);
        t.setTextColor(s.warn ? 0xFFB23C00 : 0xFF1F1F1F);
        if (s.warn) t.setTypeface(null, Typeface.BOLD);
        col.addView(t);
        if (s.detail != null && !s.detail.isEmpty()) {
            TextView d = new TextView(this);
            d.setText(s.detail);
            d.setTextSize(12.5f);
            d.setTextColor(0xFF7A7A7A);
            d.setPadding(0, dp(3), 0, 0);
            col.addView(d);
        }
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (clickable) {
            TextView arrow = new TextView(this);
            arrow.setText("›");
            arrow.setTextSize(18);
            arrow.setTextColor(0xFF8A8A8A);
            row.addView(arrow);
            row.setOnClickListener(v -> {
                pendingAction = s.action;
                finish();
            });
        }
        return row;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
