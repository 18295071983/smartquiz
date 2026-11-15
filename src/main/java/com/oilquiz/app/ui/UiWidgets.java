package com.oilquiz.app.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.widget.TextViewCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;

/**
 * 控件工厂（2026-11-16）：把"各页面各写"的常用控件收敛成统一入口。
 *
 * <p><b>背景</b>：审计里 Spinner 382 处、ChipGroup 13 处、TextInputLayout 16 处、
 * AutoCompleteTextView 19 处、RatingBar 3 处，全部散用且样式各异；Tooltip 4 处散用、
 * 文本选择各页各写。</p>
 *
 * <p>这些工厂方法统一做三件事：<b>取主题色</b>（不是固定资源）、<b>用 token 尺寸</b>、
 * <b>按键可点/可访问</b>（补 contentDescription 与最小触摸尺寸）。改造老页面时把
 * {@code new Spinner(ctx)} 换成 {@link #spinner} 即可，外观立刻统一。</p>
 */
public final class UiWidgets {

    private UiWidgets() {
    }

    /**
     * 统一下拉选择（Spinner 的替代构建器）。
     *
     * @param items     选项文本
     * @param onSelect  选中回调（index）
     */
    public static Spinner spinner(Context context, String[] items, IntConsumer onSelect) {
        Spinner spinner = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context,
                android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                Tokens.applyText(tv, Tokens.TEXT_BODY_LG, Tokens.Weight.REGULAR,
                        ThemeColors.get(context, R.color.text_primary));
                return tv;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                Tokens.applyText(tv, Tokens.TEXT_BODY, Tokens.Weight.REGULAR,
                        ThemeColors.get(context, R.color.text_primary));
                int p = Tokens.space(context, Tokens.SPACE_LG);
                tv.setPadding(p, p, p, p);
                return tv;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        // 下拉框背景统一成主题卡片底 + 圆角
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Tokens.radius(context, Tokens.RADIUS_MD));
        bg.setColor(ThemeColors.get(context, R.color.card_background));
        bg.setStroke(1, ThemeColors.get(context, R.color.border));
        spinner.setBackground(bg);
        int p = Tokens.space(context, Tokens.SPACE_MD);
        spinner.setPadding(p, p, p, p);
        if (onSelect != null) {
            spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                           int position, long id) {
                    onSelect.accept(position);
                }

                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {
                }
            });
        }
        return spinner;
    }

    /** 统一输入框（TextInputLayout + EditText），带主题描边与错误态样式。 */
    public static TextInputLayout input(Context context, String hint, String initialValue) {
        TextInputLayout layout = new TextInputLayout(context);
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxCornerRadii(Tokens.radius(context, Tokens.RADIUS_MD),
                Tokens.radius(context, Tokens.RADIUS_MD),
                Tokens.radius(context, Tokens.RADIUS_MD),
                Tokens.radius(context, Tokens.RADIUS_MD));
        layout.setBoxStrokeColor(ThemeColors.get(context, R.color.primary));
        layout.setHintTextColor(android.content.res.ColorStateList.valueOf(
                ThemeColors.get(context, R.color.text_secondary)));

        TextInputEditText edit = new TextInputEditText(context);
        Tokens.applyText(edit, Tokens.TEXT_BODY_LG, Tokens.Weight.REGULAR,
                ThemeColors.get(context, R.color.text_primary));
        if (initialValue != null) {
            edit.setText(initialValue);
        }
        edit.setBackground(null);
        layout.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return layout;
    }

    /** 统一 Chip（选中态/未选中态都用主题色，替代各页自绘 pill）。 */
    public static Chip chip(Context context, String text, boolean checkable, boolean checked) {
        Chip chip = new Chip(context);
        chip.setText(text);
        chip.setCheckable(checkable);
        chip.setChecked(checked);
        chip.setChipCornerRadius(Tokens.radius(context, Tokens.RADIUS_PILL));
        chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                ThemeColors.get(context, R.color.card_background)));
        chip.setChipStrokeWidth(1f);
        chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(
                ThemeColors.get(context, R.color.border)));
        chip.setTextColor(ThemeColors.get(context, R.color.text_primary));
        chip.setEnsureMinTouchTargetSize(true);
        return chip;
    }

    /** 统一 ChipGroup（单行多选/单选）。 */
    public static ChipGroup chipGroup(Context context, boolean singleSelection) {
        ChipGroup group = new ChipGroup(context);
        group.setSingleSelection(singleSelection);
        group.setSingleLine(false);
        return group;
    }

    /** 可选中的文本（长按选择/复制），用于消息内容、代码块、地址等。 */
    public static TextView selectableText(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        TextViewCompat.setTextAppearance(tv, android.R.style.TextAppearance_Medium);
        tv.setTextIsSelectable(true);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        return tv;
    }

    /**
     * 无障碍描述 + Tooltip 一体化：长按显示提示文案，同时给读屏器一个描述。
     * （项目里 Tooltip 只有 4 处、contentDescription 11 处，这里给统一入口）
     */
    public static void tooltip(View view, String text) {
        if (view == null || text == null) {
            return;
        }
        view.setContentDescription(text);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            view.setTooltipText(text);
        }
    }

    /** 统一 "无数据" 占位块（骨架屏之外的静态空状态）。 */
    public static View emptyState(Context context, String message) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int p = Tokens.space(context, Tokens.SPACE_3XL);
        box.setPadding(p, p, p, p);

        TextView tv = new TextView(context);
        tv.setText(message);
        tv.setGravity(Gravity.CENTER);
        Tokens.applyText(tv, Tokens.TEXT_BODY, Tokens.Weight.REGULAR,
                ThemeColors.get(context, R.color.text_secondary));
        box.addView(tv);
        return box;
    }

    /** 简易 IntConsumer（避免为了一个回调引入 java.util.function 的 API 差异）。 */
    public interface IntConsumer {
        void accept(int value);
    }
}
