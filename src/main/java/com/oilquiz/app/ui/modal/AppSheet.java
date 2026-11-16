package com.oilquiz.app.ui.modal;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;

/**
 * 标准底部弹层（2026-11-16 统一）。
 *
 * <p><b>为什么有它</b>：对话页/设置页此前各写各的 {@code BottomSheetDialog}（顶部圆角、拖拽条、
 * 标题字号、按钮颜色每个页面一份），换肤/深色下有的跟主题、有的写死白底。这里给统一长什么样：</p>
 *
 * <ul>
 *   <li>顶部圆角 24dp、底色 {@code ?attr/colorSurface}、拖拽条 {@code colorControlTextHint}；</li>
 *   <li>标题走 {@link Tokens#TEXT_SUBTITLE}+SEMIBOLD，副标题 {@link Tokens#TEXT_FOOTNOTE}+次要色；</li>
 *   <li>主按钮实心 {@code colorPrimary/colorOnPrimary}，次按钮文字色 {@code colorPrimary}；</li>
 *   <li>取消/返回键与 {@code cancelable} 一致，内容区可放任意 View。</li>
 * </ul>
 *
 * <p>用法：</p>
 * <pre>
 * AppSheet.builder(this)
 *         .title("选择操作")
 *         .content(row)
 *         .action("确定", true, v -&gt; { ... })
 *         .action("取消", false, null)
 *         .show();
 * </pre>
 */
public final class AppSheet {

    public interface Action {
        void onClick(View v);
    }

    public static Builder builder(Context context) {
        return new Builder(context);
    }

    public static final class Builder {
        private final Context context;
        private final BottomSheetDialog dialog;
        private final LinearLayout root;
        private final LinearLayout header;
        private final LinearLayout contentWrap;
        private final LinearLayout actionsWrap;
        private TextView titleView;
        private TextView subtitleView;
        private boolean cancelable = true;

        private Builder(Context context) {
            this.context = context;
            this.dialog = new BottomSheetDialog(context);

            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(ThemeColors.get(context, R.color.surface));
            float radius = Tokens.radiusXl(context);
            bg.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
            root.setBackground(bg);
            int padH = Tokens.space2xl(context);
            root.setPadding(padH, Tokens.spaceSm(context), padH,
                    Tokens.space2xl(context) + Tokens.spaceMd(context));

            // 拖拽条（标准 32×4dp、提示色 30% 透明）
            View handle = new View(context);
            GradientDrawable handleBg = new GradientDrawable();
            handleBg.setCornerRadius(Tokens.radiusPill(context));
            handleBg.setColor(withAlpha(ThemeColors.get(context, R.color.text_hint), 0.35f));
            handle.setBackground(handleBg);
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                    Tokens.space3xl(context), Math.max(2, Tokens.spaceXs(context)));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handleLp.bottomMargin = Tokens.spaceMd(context);
            root.addView(handle, handleLp);

            header = new LinearLayout(context);
            header.setOrientation(LinearLayout.VERTICAL);
            root.addView(header, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            contentWrap = new LinearLayout(context);
            contentWrap.setOrientation(LinearLayout.VERTICAL);
            contentWrap.setPadding(0, Tokens.spaceSm(context), 0, Tokens.spaceSm(context));
            root.addView(contentWrap, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            actionsWrap = new LinearLayout(context);
            actionsWrap.setOrientation(LinearLayout.HORIZONTAL);
            actionsWrap.setGravity(Gravity.END);
            root.addView(actionsWrap, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            dialog.setContentView(root);
        }

        public Builder title(CharSequence title) {
            if (titleView == null) {
                titleView = new TextView(context);
                Tokens.applyText(titleView, Tokens.TEXT_SUBTITLE, Tokens.Weight.SEMIBOLD,
                        ThemeColors.get(context, R.color.text_primary));
                header.addView(titleView);
            }
            titleView.setText(title);
            return this;
        }

        public Builder subtitle(CharSequence subtitle) {
            if (subtitleView == null) {
                subtitleView = new TextView(context);
                Tokens.applyText(subtitleView, Tokens.TEXT_FOOTNOTE, Tokens.Weight.REGULAR,
                        ThemeColors.get(context, R.color.text_secondary));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Tokens.spaceXs(context);
                header.addView(subtitleView, lp);
            }
            subtitleView.setText(subtitle);
            return this;
        }

        public Builder content(View content) {
            if (content != null) {
                contentWrap.addView(content, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            return this;
        }

        public Builder action(CharSequence text, boolean primary, Action action) {
            MaterialButton button = new MaterialButton(context);
            button.setText(text);
            button.setAllCaps(false);
            button.setCornerRadius(Tokens.radiusMd(context));
            if (primary) {
                button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        ThemeColors.get(context, R.color.primary)));
                button.setTextColor(ThemeColors.attr(context, R.attr.colorOnPrimary));
            } else {
                button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0));
                button.setTextColor(ThemeColors.get(context, R.color.primary));
            }
            if (action != null) {
                button.setOnClickListener(v -> {
                    action.onClick(v);
                    dialog.dismiss();
                });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Tokens.spaceSm(context);
            actionsWrap.addView(button, lp);
            return this;
        }

        public Builder cancelable(boolean cancelable) {
            this.cancelable = cancelable;
            dialog.setCancelable(cancelable);
            dialog.setCanceledOnTouchOutside(cancelable);
            return this;
        }

        public Builder onDismiss(android.content.DialogInterface.OnDismissListener listener) {
            dialog.setOnDismissListener(listener);
            return this;
        }

        public AppSheet show() {
            dialog.setCancelable(cancelable);
            dialog.setCanceledOnTouchOutside(cancelable);
            dialog.show();
            // 2026-11-16 IME 标准：弹层内容里可能有输入框（重命名/搜索等），
            // 键盘弹出时给根容器补 ime 底部内边距（BottomSheetDialog 默认不一定 resize）。
            try {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setSoftInputMode(
                            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                }
                final int padBottomBase = root.getPaddingBottom();
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                    int ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
                    if (ime > 0) {
                        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                                v.getPaddingRight(), padBottomBase + ime);
                    } else {
                        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                                v.getPaddingRight(), padBottomBase);
                    }
                    return insets;
                });
                androidx.core.view.ViewCompat.requestApplyInsets(root);
            } catch (Throwable ignored) {
                // IME 适配失败不影响弹层使用
            }
            return new AppSheet(dialog);
        }

        private static int withAlpha(int color, float alpha) {
            int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
            return (color & 0x00FFFFFF) | (a << 24);
        }
    }

    private final BottomSheetDialog dialog;

    private AppSheet(BottomSheetDialog dialog) {
        this.dialog = dialog;
    }

    public void dismiss() {
        dialog.dismiss();
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    public BottomSheetDialog dialog() {
        return dialog;
    }
}
