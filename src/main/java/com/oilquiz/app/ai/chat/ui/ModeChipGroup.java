package com.oilquiz.app.ai.chat.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.List;

/**
 * 模式芯片组（全局可复用 View 组件，新建通用设计）。
 *
 * 覆盖 AI 对话页三枚模式芯片（Agent / 联网搜索 / 深度思考）的通用形态：
 * 组合选中态互斥 + 点击回调。页面专属配色通过 {@link #setChipStyle} 注入
 * （主题系统接入点），默认跟随 Material 主题。
 *
 * <pre>
 * ModeChipGroup group = new ModeChipGroup(context);
 * group.setChipStyle(enabledColor, disabledColor, radiusDp);
 * group.setChecked(Mode.AGENT);
 * group.setOnModeChanged(mode -> { ... });
 * </pre>
 */
public class ModeChipGroup extends LinearLayout {

    /** 芯片模式 */
    public enum Mode { AGENT, WEB_SEARCH, DEEP_THINK }

    public interface OnModeChanged { void onModeChanged(Mode mode); }

    private final List<Chip> chips = new ArrayList<>();
    private OnModeChanged onModeChanged;
    private Mode current = Mode.AGENT;
    private int checkedTint = 0xFF2563EB;   // 选中：主题主色（默认蓝）
    private int uncheckedTint = 0xFFE5E7EB; // 未选：主题浅灰

    public ModeChipGroup(Context context) { this(context, null); }

    public ModeChipGroup(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        buildChips();
    }

    private void buildChips() {
        addChip(Mode.AGENT, "智能体");
        addChip(Mode.WEB_SEARCH, "联网");
        addChip(Mode.DEEP_THINK, "深度思考");
        refresh();
    }

    private void addChip(final Mode mode, String label) {
        Chip chip = new Chip(getContext());
        chip.setText(label);
        chip.setClickable(true);
        chip.setCheckable(true);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setChipMinHeight(dp(34));
        int pad = dp(4);
        chip.setChipStartPadding(pad);
        chip.setChipEndPadding(pad);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        addView(chip, lp);
        chip.setOnClickListener(v -> setChecked(mode));
        chips.add(chip);
    }

    /** 注入主题配色（跟随应用主题系统的接入点；调用后自动刷新） */
    public void setChipStyle(int checkedTint, int uncheckedTint, float radiusDp) {
        this.checkedTint = checkedTint;
        this.uncheckedTint = uncheckedTint;
        for (Chip c : chips) {
            c.setChipBackgroundColor(ColorStateList.valueOf(uncheckedTint));
            c.setChipStrokeColor(ColorStateList.valueOf(checkedTint));
            c.setChipStrokeWidth(dp(1));
            c.setChipCornerRadius(dp(radiusDp));
        }
        refresh();
    }

    public void setChecked(Mode mode) {
        if (current == mode && !mode.equals(Mode.AGENT)) { /* 允许重复点击回调 */ }
        current = mode;
        refresh();
        if (onModeChanged != null) onModeChanged.onModeChanged(mode);
    }

    public Mode getCurrentMode() { return current; }

    public void setOnModeChanged(OnModeChanged l) { this.onModeChanged = l; }

    private void refresh() {
        for (int i = 0; i < chips.size(); i++) {
            Chip c = chips.get(i);
            boolean checked = (i == current.ordinal());
            c.setChecked(checked);
            c.setTextColor(checked ? checkedTint : 0xFF6B7280);
            c.setChipBackgroundColor(ColorStateList.valueOf(checked ? (checkedTint & 0x22FFFFFF) : uncheckedTint));
            c.setChipStrokeColor(ColorStateList.valueOf(checked ? checkedTint : 0x00000000));
            c.setChipStrokeWidth(dp(checked ? 1 : 0));
        }
    }

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
}
