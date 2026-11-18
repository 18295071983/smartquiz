package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;
import com.oilquiz.app.ui.modal.AppCard;

/**
 * 画布控件工厂（2026-11-18）：把动态画布（{@code layout_canvas} / layout 树 / 临时 layout）
 * 里的表单控件，从"渲染器自己 {@code new} 平台控件 + 手绘 GradientDrawable"收敛到
 * <b>框架标准层</b>——Material M2 组件 + {@link Tokens} + {@link ComponentColors}。
 *
 * <p><b>为什么要有这一层</b>：{@code NativeLayoutRenderer} 之前直接用
 * {@code new android.widget.Button/EditText/Switch/SeekBar}，背景是手绘圆角。结果是画布里
 * 的控件"只有颜色跟着主题、形态和交互是另一套"：按钮没有 M2 涟漪/禁用态、输入框没有浮动
 * 标签/错误态/密码可见切换、开关还是老式 Switch、卡片是手绘 LinearLayout 而不是
 * {@link MaterialCardView}。外壳（{@code AppDialog}）明明是框架的，内容却不是。</p>
 *
 * <p><b>为什么全部程序化配置、不传 {@code R.style.*}</b>：{@code new MaterialButton(ctx, null, X)}
 * 的第三个参数是 <b>defStyleAttr（主题属性）</b>，不是样式资源；传 {@code R.style.Widget_App_Button_Canvas}
 * 会静默失效（真机实测：按钮退回默认样式、输入框的描边/圆角全丢，只剩一条 EditText 下划线）。
 * 组件样式只有走 XML 的 {@code style=} 才生效，代码创建时必须在构造后显式 set。</p>
 *
 * <p><b>约束</b></p>
 * <ol>
 *   <li>只换控件实现，<b>不改 layout JSON 契约</b>：节点字段（key/label/hint/value/options…）
 *       与 {@link com.oilquiz.app.ai.python.NativeLayoutRenderer#collectValues} 的取值口径不变；</li>
 *   <li>颜色一律走 {@link ComponentColors}（内部是主题 attr 优先），不写死十六进制；</li>
 *   <li>字号/间距/圆角走 {@link Tokens} 档位，不写字面量；</li>
 *   <li>{@code key} 的值引用仍登记 {@link EditText}/{@link android.widget.CompoundButton} 等
 *       <b>平台基类</b>，保证旧的取值逻辑继续命中。</li>
 * </ol>
 *
 * <p>注意：本类只负责"造控件"。点击/取值/回填分别由
 * {@code NativeLayoutRenderer.wireActionClick / collectValues / applyValues} 负责。</p>
 */
public final class CanvasControls {

    private CanvasControls() {
    }

    // ==================== 按钮 ====================

    /** 画布里的提交按钮（M2 {@link MaterialButton}，实心主色）。 */
    public static MaterialButton button(Context context, String text) {
        MaterialButton b = new MaterialButton(context);
        int primary = ComponentColors.accent(context);
        int onPrimary = onPrimaryColor(context);
        b.setText(text == null ? "" : text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.textSizeSp(context, Tokens.TEXT_BODY));
        b.setTextColor(onPrimary);
        b.setBackgroundTintList(ColorStateList.valueOf(primary));
        b.setRippleColor(ColorStateList.valueOf(alpha(primary, 0x33)));
        b.setCornerRadius(Tokens.radiusMd(context));
        b.setElevation(0f);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        b.setMinHeight(dp(context, 44));
        b.setMinimumHeight(dp(context, 44));
        return b;
    }

    /**
     * 画布里的次要按钮（M2 描边按钮，用于步进器 +/-、分段选择）。
     * <p>会打上"画布自有交互"标记：宿主遍历 {@code Button} 绑提交逻辑时会跳过它，
     * 否则点 +/- 会被当成"提交整张画布"。</p>
     */
    public static MaterialButton secondaryButton(Context context, String text) {
        MaterialButton b = new MaterialButton(context);
        int primary = ComponentColors.accent(context);
        b.setText(text == null ? "" : text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.textSizeSp(context, Tokens.TEXT_BODY));
        b.setTextColor(primary);
        b.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        b.setStrokeWidth(dp(context, 1));
        b.setStrokeColor(ColorStateList.valueOf(primary));
        b.setRippleColor(ColorStateList.valueOf(alpha(primary, 0x26)));
        b.setCornerRadius(Tokens.radiusMd(context));
        b.setElevation(0f);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        b.setMinWidth(dp(context, 40));
        b.setMinimumWidth(dp(context, 40));
        b.setMinHeight(dp(context, 40));
        b.setMinimumHeight(dp(context, 40));
        markOwned(b);
        return b;
    }

    // ==================== 文本输入 ====================

    /** 单行输入（无特殊键盘类型）。 */
    public static TextInputLayout input(Context context, String label, String placeholder, String value) {
        return input(context, label, placeholder, value, 0, false, false);
    }

    /**
     * 画布输入框：M2 outlined {@link TextInputLayout} + {@link TextInputEditText}。
     *
     * @param label         浮动标签（为空时退回 placeholder 当标签）
     * @param placeholder   占位提示（label 为空时它同时当标签）
     * @param value         初始值
     * @param inputType     {@link InputType} 组合；0 = 保持默认（文本）
     * @param multiline     多行（2~6 行）
     * @param passwordToggle 密码可见切换（end icon）
     * @return TextInputLayout；取内部输入框用 {@link #editOf(TextInputLayout)}
     */
    public static TextInputLayout input(Context context, String label, String placeholder,
                                        String value, int inputType, boolean multiline,
                                        boolean passwordToggle) {
        TextInputLayout til = newOutlinedField(context);
        String hint = label != null && !label.isEmpty() ? label : placeholder;
        if (hint != null && !hint.isEmpty()) {
            til.setHint(hint);
        }
        TextInputEditText et = newEditText(context);
        if (inputType != 0) {
            et.setInputType(inputType);
        }
        if (multiline) {
            et.setSingleLine(false);
            et.setMinLines(2);
            et.setMaxLines(6);
            et.setGravity(Gravity.TOP | Gravity.START);
        } else {
            et.setSingleLine(true);
        }
        if (passwordToggle) {
            til.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
            til.setEndIconTintList(ColorStateList.valueOf(ComponentColors.textSecondary(context)));
        }
        til.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 挂载后再回填（TIL.addView 会刷新 hint 展示，先 setText 可能被覆盖成空）
        if (value != null && !value.isEmpty()) {
            et.setText(value);
        }
        bindTapToEdit(til, et);
        return til;
    }

    /** 搜索条：M2 outlined 输入框 + 前置搜索图标。 */
    public static TextInputLayout searchBar(Context context, String hint, String value) {
        TextInputLayout til = newOutlinedField(context);
        til.setHint(hint == null || hint.isEmpty() ? "搜索" : hint);
        til.setStartIconDrawable(R.drawable.ic_search);
        til.setStartIconTintList(ColorStateList.valueOf(ComponentColors.textSecondary(context)));
        TextInputEditText et = newEditText(context);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        if (value != null && !value.isEmpty()) {
            et.setText(value);
        }
        til.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bindTapToEdit(til, et);
        return til;
    }

    /**
     * 只读选择字段（日期/时间用）：M2 outlined 输入框 + 下拉箭头，
     * 只负责"看起来是表单字段"，点击行为由调用方挂。
     */
    public static TextInputLayout pickerField(Context context, String hint, String value) {
        TextInputLayout til = newOutlinedField(context);
        til.setHint(hint == null ? "" : hint);
        // 不用 END_ICON_DROPDOWN_MENU：那个模式要求内部是 AutoCompleteTextView，普通
        // TextInputEditText 会直接抛 "EditText needs to be an AutoCompleteTextView"。
        // 这里用自定义箭头，语义就是"可点开的选择字段"。
        til.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        til.setEndIconDrawable(R.drawable.ic_chevron_down);
        til.setEndIconTintList(ColorStateList.valueOf(ComponentColors.textSecondary(context)));
        TextInputEditText et = newEditText(context);
        et.setText(value == null ? "" : value);
        et.setInputType(InputType.TYPE_NULL);
        et.setFocusable(false);
        et.setClickable(false);
        et.setLongClickable(false);
        et.setCursorVisible(false);
        til.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return til;
    }

    /** 取 {@link #input}/{@link #searchBar}/{@link #pickerField} 内部的输入框（登记 viewRefs 用）。 */
    public static EditText editOf(TextInputLayout til) {
        return til == null ? null : til.getEditText();
    }

    // ==================== 选择类 ====================

    /**
     * M2 下拉选择（exposed dropdown）：outlined 输入框 + {@link MaterialAutoCompleteTextView}。
     *
     * <p>为什么不用 {@code Spinner}：框架的 {@code UiWidgets.spinner} 会把 Spinner 背景换成
     * 纯色圆角，下拉箭头随之丢失（真机实测看起来就是一个没有箭头的输入框）；M2 的标准做法是
     * TextInputLayout + AutoCompleteTextView + END_ICON_DROPDOWN_MENU，箭头/弹层/主题都由框架给。</p>
     *
     * <p>取值口径：AutoCompleteTextView 继承 EditText，{@code collectValues} 的 EditText 分支
     * 直接读当前文本（= 选中项），不需要额外分支。</p>
     */
    public static TextInputLayout dropdown(Context context, String label, List<String> items,
                                           String value) {
        TextInputLayout til = newOutlinedField(context);
        til.setHint(label == null || label.isEmpty() ? "请选择" : label);
        til.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
        til.setEndIconTintList(ColorStateList.valueOf(ComponentColors.textSecondary(context)));
        MaterialAutoCompleteTextView et = new MaterialAutoCompleteTextView(til.getContext());
        et.setSimpleItems(items == null ? new String[0] : items.toArray(new String[0]));
        et.setInputType(InputType.TYPE_NULL);
        et.setSingleLine(true);
        // 只读下拉：点开弹层即可，不要弹输入法
        et.setShowSoftInputOnFocus(false);
        et.setBackground(null);
        Tokens.applyText(et, Tokens.TEXT_BODY, Tokens.Weight.REGULAR,
                ComponentColors.textPrimary(context));
        til.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 必须在挂到 TextInputLayout 之后再回填：addView 会触发 TIL 重建/刷新 hint 展示，
        // 先 setText 会被覆盖成空（真机实测：下拉里已选的值不显示，只剩 hint）
        if (value != null && !value.isEmpty()) {
            et.setText(value, false);
        }
        return til;
    }

    /** M2 开关。 */
    public static SwitchMaterial switchView(Context context, String text, boolean checked) {
        SwitchMaterial sw = new SwitchMaterial(context);
        if (text != null && !text.isEmpty()) {
            sw.setText(text);
        }
        sw.setChecked(checked);
        Tokens.applyText(sw, Tokens.TEXT_BODY, Tokens.Weight.REGULAR, ComponentColors.textPrimary(context));
        // 2026-11-18（真机）：画布在 ScrollView + WRAP 测量下，Switch 的 track 固有宽度拿不到，
        // 只画出半个 thumb（灰色 "D"）。显式给最小宽度 + 品牌色 tint，保证任何宿主都画得完整。
        int accent = ComponentColors.accent(context);
        sw.setSwitchMinWidth(dp(context, 52));
        sw.setMinWidth(dp(context, 52));
        sw.setThumbTintList(ColorStateList.valueOf(accent));
        sw.setTrackTintList(ColorStateList.valueOf(alpha(accent, 0x66)));
        return sw;
    }

    /** M2 复选框。 */
    public static MaterialCheckBox checkBox(Context context, String text, boolean checked) {
        MaterialCheckBox cb = new MaterialCheckBox(context);
        cb.setText(text == null ? "" : text);
        cb.setChecked(checked);
        Tokens.applyText(cb, Tokens.TEXT_BODY, Tokens.Weight.REGULAR, ComponentColors.textPrimary(context));
        return cb;
    }

    /** M2 单选框。 */
    public static MaterialRadioButton radioButton(Context context, String text) {
        MaterialRadioButton rb = new MaterialRadioButton(context);
        rb.setText(text == null ? "" : text);
        Tokens.applyText(rb, Tokens.TEXT_BODY, Tokens.Weight.REGULAR, ComponentColors.textPrimary(context));
        return rb;
    }

    /** M2 Chip（标签/分段选择）。checkable=false 时用于 tag_input 的可关闭标签。 */
    public static Chip chip(Context context, String text, boolean checkable, boolean checked) {
        Chip chip = new Chip(context);
        chip.setText(text == null ? "" : text);
        chip.setCheckable(checkable);
        chip.setChecked(checked);
        chip.setChipCornerRadius(Tokens.radiusPill(context));
        chip.setChipStrokeWidth(dp(context, 1));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.textSizeSp(context, Tokens.TEXT_BODY));
        chip.setEnsureMinTouchTargetSize(true);
        if (checkable) {
            // 选中态：主色底 + onPrimary 文字（不再写死白色，换肤/深色下都读得到）
            int[][] states = new int[][]{{android.R.attr.state_checked}, {}};
            int accent = ComponentColors.accent(context);
            chip.setChipBackgroundColor(new ColorStateList(states,
                    new int[]{accent, ComponentColors.fieldBg(context)}));
            chip.setChipStrokeColor(new ColorStateList(states,
                    new int[]{accent, ComponentColors.border(context)}));
            chip.setTextColor(new ColorStateList(states, new int[]{
                    onPrimaryColor(context), ComponentColors.textPrimary(context)}));
            chip.setCheckedIconVisible(false);
        } else {
            chip.setChipBackgroundColor(ColorStateList.valueOf(ComponentColors.fieldBg(context)));
            chip.setChipStrokeColor(ColorStateList.valueOf(ComponentColors.border(context)));
            chip.setTextColor(ComponentColors.textPrimary(context));
        }
        return chip;
    }

    /** Chip 容器（单选用 singleSelection=true）。 */
    public static ChipGroup chipGroup(Context context, boolean singleSelection) {
        ChipGroup group = new ChipGroup(context);
        group.setSingleSelection(singleSelection);
        group.setSelectionRequired(false);
        group.setSingleLine(false);
        return group;
    }

    // ==================== 容器 ====================

    /** 画布卡片：框架标准卡（{@link AppCard}）。内边距由渲染器按节点 padding 决定（默认 12/8dp）。 */
    public static MaterialCardView card(Context context) {
        MaterialCardView card = AppCard.create(context);
        // 用 setPadding 而不是 contentPadding：画布节点可能带 style.padding，渲染器会直接 setPadding
        card.setContentPadding(0, 0, 0, 0);
        return card;
    }

    // ==================== 标记：画布自有交互 ====================

    /**
     * 打上"画布自有交互"标记。宿主（{@code PythonToolManager.collectButtons}）遍历 Button
     * 绑"提交整张画布"时会跳过带标记的控件——只有真正要提交的 button 节点才该被绑定。
     */
    public static void markOwned(View view) {
        if (view != null) {
            view.setTag(R.id.tag_canvas_owned, Boolean.TRUE);
        }
    }

    /** 是否带"画布自有交互"标记。 */
    public static boolean isOwned(View view) {
        return view != null && Boolean.TRUE.equals(view.getTag(R.id.tag_canvas_owned));
    }

    // ==================== 内部工具 ====================

    /**
     * 代码创建 outlined {@link TextInputLayout}：boxBackgroundMode / 圆角 / 描边色 / hint 色
     * 必须显式设置——构造函数的 defStyleAttr 只能传主题属性，传样式资源会静默失效。
     */
    private static TextInputLayout newOutlinedField(Context context) {
        TextInputLayout til = new TextInputLayout(context);
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        int r = Tokens.radiusMd(context);
        til.setBoxCornerRadii(r, r, r, r);
        int[][] states = new int[][]{
                {android.R.attr.state_focused},
                {android.R.attr.state_enabled},
                {}
        };
        int accent = ComponentColors.accent(context);
        int border = ComponentColors.border(context);
        til.setBoxStrokeColorStateList(new ColorStateList(states, new int[]{accent, border, border}));
        til.setBoxStrokeWidth(dp(context, 1));
        til.setBoxStrokeWidthFocused(dp(context, 2));
        til.setBoxBackgroundColor(Color.TRANSPARENT);
        til.setHintTextColor(ColorStateList.valueOf(ComponentColors.textSecondary(context)));
        return til;
    }

    /** 统一的内部输入框（去掉 EditText 自带下划线，交给 TextInputLayout 的描边盒）。 */
    private static TextInputEditText newEditText(Context context) {
        TextInputEditText et = new TextInputEditText(context);
        Tokens.applyText(et, Tokens.TEXT_BODY, Tokens.Weight.REGULAR, ComponentColors.textPrimary(context));
        et.setBackground(null);
        return et;
    }

    /** 点输入框边缘（TIL 的内边距）也要能聚焦并弹键盘：转成对内部 EditText 的点击。 */
    private static void bindTapToEdit(final TextInputLayout til, final TextInputEditText et) {
        til.setOnClickListener(v -> {
            et.requestFocus();
            et.performClick();
        });
    }

    /** 主题的 colorOnPrimary（取不到时退回项目色板，避免 0 = 透明字）。 */
    private static int onPrimaryColor(Context context) {
        int c = ThemeColors.attr(context, R.attr.colorOnPrimary);
        return c != 0 ? c : ThemeColors.get(context, R.color.on_primary);
    }

    /** 给颜色加透明度（0x00~0xFF）。 */
    private static int alpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
