package com.oilquiz.app.ai.chat.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用底部弹窗壳（全局可复用 View 组件，新建通用设计）。
 *
 * 覆盖对话页各 BottomSheet 弹窗（工具引导 / 复合引导 / 附件选项 / 补参 /
 * 压缩会话 / 手动路径）的公共骨架：圆角容器 + 标题 + 可选内容区 +
 * 横向操作按钮组。内容区可放任意 View。
 *
 * <pre>
 * ChatBottomSheet sheet = new ChatBottomSheet(context)
 *         .title("选择操作")
 *         .content(contentView)
 *         .action("确定", v -> { ... })
 *         .cancelable(true);
 * sheet.show();
 * </pre>
 */
public class ChatBottomSheet {

    public interface Action { void onClick(View v); }

    private final Context context;
    private final BottomSheetDialog dialog;
    private final LinearLayout root;
    private final LinearLayout contentWrap;
    private final LinearLayout actionsWrap;

    private boolean shown;

    public ChatBottomSheet(Context context) {
        this.context = context;
        dialog = new BottomSheetDialog(context);

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadii(new float[]{dp(20), dp(20), 0, 0, 0, 0, 0, 0});
        root.setBackground(bg);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad + dp(8));

        contentWrap = new LinearLayout(context);
        contentWrap.setOrientation(LinearLayout.VERTICAL);
        contentWrap.setPadding(0, dp(4), 0, dp(12));
        root.addView(contentWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        actionsWrap = new LinearLayout(context);
        actionsWrap.setOrientation(LinearLayout.HORIZONTAL);
        actionsWrap.setGravity(Gravity.END);
        root.addView(actionsWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(root);
    }

    /** 标题（可多次调用：后续调用为追加的说明行） */
    public ChatBottomSheet title(String title) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setTextColor(0xFF111827);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setPadding(0, 0, 0, dp(6));
        contentWrap.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return this;
    }

    /** 说明文字（次标题/正文） */
    public ChatBottomSheet message(String msg) {
        TextView tv = new TextView(context);
        tv.setText(msg);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(0xFF4B5563);
        tv.setLineSpacing(dp(2), 1.0f);
        tv.setPadding(0, 0, 0, dp(4));
        contentWrap.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return this;
    }

    /** 自定义内容区（追加到底部） */
    public ChatBottomSheet content(View view) {
        contentWrap.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return this;
    }

    /** 追加操作按钮（自右向左排列；首个追加在最右） */
    public ChatBottomSheet action(String label, @Nullable Action action) {
        TextView btn = new TextView(context);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setTextColor(0xFF2563EB);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(rounded(0xFFEEF2FF, dp(12)));
        btn.setPadding(dp(18), dp(10), dp(18), dp(10));
        int idx = actionsWrap.getChildCount();
        actionsWrap.addView(btn, 0, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) btn.getLayoutParams();
        lp.setMarginStart(idx == 0 ? 0 : dp(10));
        btn.setOnClickListener(v -> {
            if (action != null) action.onClick(v);
            else dialog.dismiss();
        });
        return this;
    }

    /** 次要（灰色）按钮 */
    public ChatBottomSheet secondaryAction(String label, @Nullable Action action) {
        TextView btn = new TextView(context);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setTextColor(0xFF6B7280);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(rounded(0xFFF3F4F6, dp(12)));
        btn.setPadding(dp(18), dp(10), dp(18), dp(10));
        int idx = actionsWrap.getChildCount();
        actionsWrap.addView(btn, 0, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) btn.getLayoutParams();
        lp.setMarginStart(idx == 0 ? 0 : dp(10));
        btn.setOnClickListener(v -> {
            if (action != null) action.onClick(v);
            else dialog.dismiss();
        });
        return this;
    }

    public ChatBottomSheet cancelable(boolean cancelable) {
        dialog.setCancelable(cancelable);
        return this;
    }

    public ChatBottomSheet onDismiss(@Nullable Runnable r) {
        dialog.setOnDismissListener(d -> { if (r != null) r.run(); });
        return this;
    }

    public void show() {
        if (shown) return;
        shown = true;
        dialog.show();
    }

    public void dismiss() { dialog.dismiss(); }

    public boolean isShowing() { return dialog.isShowing(); }

    private android.graphics.drawable.GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.getResources().getDisplayMetrics()); }
}
