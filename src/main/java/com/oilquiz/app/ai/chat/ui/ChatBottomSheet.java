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
