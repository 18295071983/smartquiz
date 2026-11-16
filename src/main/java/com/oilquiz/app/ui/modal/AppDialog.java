package com.oilquiz.app.ui.modal;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;

import androidx.appcompat.app.AlertDialog;

/**
 * 标准模态框（2026-11-16 统一）。
 *
 * <p><b>为什么有它</b>：全站手写了 90+ 处 {@code com.oilquiz.app.ui.modal.AppDialog.builder(...)} 和
 * 17 处 {@code MaterialAlertDialogBuilder}，每个页面自己定 padding、按钮文案、能否点外部关闭，
 * 结果是同类确认框长得不一样、深色/换肤下行为也不一致。这里给一个统一入口：</p>
 *
 * <ul>
 *   <li>外观：用 Material 对话框主题（{@code materialAlertDialogTheme} 已接 ThemeOverlay），
 *       自定义内容统一加 24dp 内边距（{@link Tokens#space2xl}），圆角/底色跟着主题；</li>
 *   <li>行为：默认 {@code cancelable} 与系统返回键一致，按钮点完自动 dismiss；</li>
 *   <li>换肤：按钮色/文字色都由 Material 对话框主题的 ?attr 解析，不写死颜色。</li>
 * </ul>
 *
 * <p>用法：</p>
 * <pre>
 * AppDialog.builder(this)
 *         .title("删除会话")
 *         .message("删除后不可恢复，确定？")
 *         .positive("删除", v -&gt; doDelete())
 *         .negative("取消", null)
 *         .show();
 * </pre>
 */
public final class AppDialog {

    private AppDialog() {
    }

    public static Builder builder(Context context) {
        return new Builder(context, 0);
    }

    /** 兼容旧代码 {@code AlertDialog.Builder(ctx, themeResId)} 的写法。 */
    public static Builder builder(Context context, int themeResId) {
        return new Builder(context, themeResId);
    }

    /** 简单提示框（只有确定按钮）。 */
    public static AlertDialog message(Context context, CharSequence title, CharSequence message) {
        return builder(context).title(title).message(message).positive(context.getString(R.string.ok), null).show();
    }

    /** 二次确认框（确定 + 取消）。 */
    public static AlertDialog confirm(Context context, CharSequence title, CharSequence message,
                                      CharSequence positive, DialogInterface.OnClickListener onPositive) {
        return builder(context)
                .title(title)
                .message(message)
                .positive(positive, onPositive)
                .negative(context.getString(R.string.cancel), null)
                .show();
    }

    public static final class Builder {
        private final Context context;
        private final MaterialAlertDialogBuilder inner;
        private boolean cancelable = true;
        private DialogInterface.OnCancelListener onCancelListener;
        private DialogInterface.OnShowListener onShowListener;

        private Builder(Context context, int themeResId) {
            this.context = context;
            this.inner = themeResId == 0
                    ? new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                    : new com.google.android.material.dialog.MaterialAlertDialogBuilder(context, themeResId);
        }

        public Builder title(CharSequence title) {
            inner.setTitle(title);
            return this;
        }

        /** 自定义内容：统一加 token 内边距，避免每个页面自己 setPadding。 */
        public Builder content(View content) {
            if (content != null) {
                int pad = Tokens.space2xl(context);
                content.setPadding(pad, Tokens.spaceMd(context), pad, 0);
                inner.setView(content);
            }
            return this;
        }

        public Builder message(CharSequence message) {
            inner.setMessage(message);
            return this;
        }

        public Builder positive(CharSequence text, DialogInterface.OnClickListener listener) {
            inner.setPositiveButton(text, listener);
            return this;
        }

        public Builder negative(CharSequence text, DialogInterface.OnClickListener listener) {
            inner.setNegativeButton(text, listener);
            return this;
        }

        public Builder items(CharSequence[] items, DialogInterface.OnClickListener listener) {
            inner.setItems(items, listener);
            return this;
        }

        public Builder singleChoiceItems(CharSequence[] items, int checkedItem,
                                          DialogInterface.OnClickListener listener) {
            inner.setSingleChoiceItems(items, checkedItem, listener);
            return this;
        }

        public Builder multiChoiceItems(CharSequence[] items, boolean[] checkedItems,
                                        DialogInterface.OnMultiChoiceClickListener listener) {
            inner.setMultiChoiceItems(items, checkedItems, listener);
            return this;
        }
        public Builder neutral(CharSequence text, DialogInterface.OnClickListener listener) {
            inner.setNeutralButton(text, listener);
            return this;
        }

        public Builder cancelable(boolean cancelable) {
            this.cancelable = cancelable;
            inner.setCancelable(cancelable);
            return this;
        }

        public Builder onDismiss(DialogInterface.OnDismissListener listener) {
            inner.setOnDismissListener(listener);
            return this;
        }

        // ---------------- 兼容 com.oilquiz.app.ui.modal.AppDialog.Builder 的旧调用名 ----------------
        // 迁移期：调用点只需把 com.oilquiz.app.ui.modal.AppDialog.builder(ctx) 换成 AppDialog.builder(ctx)，
        // 方法名先不动，降低批量改名风险；新代码仍推荐 title/message/positive 这套。
        public Builder setTitle(CharSequence title) { return title(title); }
        public Builder setMessage(CharSequence message) { return message(message); }
        public Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener l) { return positive(text, l); }
        public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener l) { return negative(text, l); }
        public Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener l) { return neutral(text, l); }
        public Builder setItems(CharSequence[] items, DialogInterface.OnClickListener l) { return items(items, l); }
        public Builder setSingleChoiceItems(CharSequence[] items, int checked, DialogInterface.OnClickListener l) { return singleChoiceItems(items, checked, l); }
        public Builder setMultiChoiceItems(CharSequence[] items, boolean[] checked, DialogInterface.OnMultiChoiceClickListener l) { return multiChoiceItems(items, checked, l); }
        public Builder setView(View content) { return content(content); }
        public Builder setCancelable(boolean cancelable) { return cancelable(cancelable); }
        public Builder setOnDismissListener(DialogInterface.OnDismissListener l) { return onDismiss(l); }
        // 资源 id 重载（旧代码大量 setTitle(R.string.x)）
        public Builder setTitle(int titleRes) { inner.setTitle(titleRes); return this; }
        public Builder setMessage(int messageRes) { inner.setMessage(messageRes); return this; }
        public Builder setPositiveButton(int textRes, DialogInterface.OnClickListener l) { inner.setPositiveButton(textRes, l); return this; }
        public Builder setNegativeButton(int textRes, DialogInterface.OnClickListener l) { inner.setNegativeButton(textRes, l); return this; }
        public Builder setNeutralButton(int textRes, DialogInterface.OnClickListener l) { inner.setNeutralButton(textRes, l); return this; }
        public Builder setIcon(int iconRes) { inner.setIcon(iconRes); return this; }
        public Builder setAdapter(android.widget.ListAdapter adapter, DialogInterface.OnClickListener l) { inner.setAdapter(adapter, l); return this; }
        public Builder setOnCancelListener(DialogInterface.OnCancelListener l) { this.onCancelListener = l; return this; }
        public Builder setOnShowListener(DialogInterface.OnShowListener l) { this.onShowListener = l; return this; }
        /** 部分旧代码先 create() 再 show()/setOnShowListener()。 */
        public AlertDialog create() { return inner.create(); }
        public AlertDialog show() {
            inner.setCancelable(cancelable);
            AlertDialog dialog = inner.show();
            if (onCancelListener != null) dialog.setOnCancelListener(onCancelListener);
            if (onShowListener != null) dialog.setOnShowListener(onShowListener);
            // 2026-11-16：标准对话框也保证键盘不盖住输入内容（自定义 content 里常有 EditText）
            try {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setSoftInputMode(
                            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                }
            } catch (Throwable ignored) {
                // 失败不影响显示
            }
            return dialog;
        }

        /** 只为取配色而暴露（子类/自定义内容需要与对话框一致的语义色）。 */
        public int colorOnSurface() {
            return ThemeColors.attr(context, R.attr.colorOnSurface);
        }
    }
}
