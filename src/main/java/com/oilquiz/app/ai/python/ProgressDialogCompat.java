package com.oilquiz.app.ai.python;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.component.ComponentColors;

/**
 * 替代已废弃的 {@code android.app.ProgressDialog}：标题 + 消息 + 水平进度条。
 *
 * <p>Python UI 的 progress 组件需要 setMessage/setMax/setProgress/getMax 这套语义，
 * 而 ProgressDialog 自 API 26 起标记废弃。这里用普通 Dialog 自绘，接口保持一致，
 * update_component 的进度刷新逻辑不用改。</p>
 */
public class ProgressDialogCompat extends Dialog {

    private final TextView titleView;
    private final TextView messageView;
    private final ProgressBar bar;

    public ProgressDialogCompat(Context context) {
        super(context);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(context, 20);
        root.setPadding(pad, pad, pad, pad);
        root.setMinimumWidth(dp(context, 280));

        titleView = new TextView(context);
        titleView.setTextSize(16);
        titleView.setTextColor(ComponentColors.textPrimary(context));
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setVisibility(View.GONE);
        root.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        messageView = new TextView(context);
        messageView.setTextSize(13);
        messageView.setTextColor(ComponentColors.textSecondary(context));
        messageView.setPadding(0, dp(context, 8), 0, dp(context, 8));
        messageView.setVisibility(View.GONE);
        root.addView(messageView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(ComponentColors.background(context)));
            window.setLayout(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    public void setTitle(CharSequence title) {
        titleView.setText(title);
        titleView.setVisibility(TextUtils.isEmpty(title) ? View.GONE : View.VISIBLE);
    }

    public void setMessage(CharSequence message) {
        messageView.setText(message);
        messageView.setVisibility(TextUtils.isEmpty(message) ? View.GONE : View.VISIBLE);
    }

    public void setMax(int max) {
        bar.setMax(max > 0 ? max : 100);
    }

    public void setIndeterminate(boolean indeterminate) {
        bar.setIndeterminate(indeterminate);
    }

    public static ProgressDialogCompat show(Context context, CharSequence title,
                                            CharSequence message, boolean indeterminate,
                                            boolean cancelable) {
        ProgressDialogCompat dialog = new ProgressDialogCompat(context);
        dialog.setTitle(title);
        dialog.setMessage(message);
        dialog.setIndeterminate(indeterminate);
        dialog.setCancelable(cancelable);
        dialog.show();
        return dialog;
    }

    public void setProgress(int progress) {
        bar.setProgress(progress);
    }

    public int getMax() {
        return bar.getMax();
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
