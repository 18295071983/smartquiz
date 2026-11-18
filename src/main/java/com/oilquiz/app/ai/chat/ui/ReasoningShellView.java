package com.oilquiz.app.ai.chat.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.ui.UiFx;
import com.oilquiz.app.ui.widget.LiveDotsView;

/**
 * 思考块自己的容器（2026-11-18）。
 *
 * <p><b>为什么需要</b>：parts 路径的思考段原来是"一个裸 TextView 当折叠头 + 正文直接挂在
 * LinearLayout 上"，没有行容器、没有箭头控件、正文也没有左侧引导线——想加展开动画/悬停底色/
 * 展开指示器时无处可挂（用户原话："思考过程及思考详情，没有自己的容器。动画样式等不好做"）。
 * 本类按 v2 设计稿（{@code docs/ui/ai-chat-redesign.md} §思考行）把结构补回来：</p>
 *
 * <pre>
 * ReasoningShellView
 *  ├─ headerRow（32dp 胶囊：✦ 图标 + 呼吸点 + 状态文字 + ⌄ 箭头；整行可点）
 *  └─ bodyHost（正文容器：左侧 2dp 引导线，展开时高度动画）
 *       └─ 正文（MarkdownWebView 或原生 TextView，由渲染器塞进来）
 * </pre>
 *
 * <p><b>动画口径</b>：展开/收起用高度动画（{@link UiFx#animateHeight}，180–200ms）+ 箭头旋转；
 * 系统关闭动画时直接切可见性。只在"状态真的变了"时播——流式每 120ms 重绑不会反复展开。</p>
 *
 * <p>配色/字号全部走主题属性与 {@code @dimen} 令牌，本类不写死任何色值（见 UiHardcodeGuardTest）。</p>
 */
public final class ReasoningShellView extends LinearLayout {

    /** 展开/收起的高度动画时长：与 UiFx 的入场口径同档（180–220ms）。 */
    private static final long EXPAND_MS = 200L;
    private static final long COLLAPSE_MS = 180L;

    private final LinearLayout headerBox;
    private final LinearLayout headerRow;
    private final TextView label;
    private final TextView summary;
    private final ImageView chevron;
    private final LiveDotsView dots;
    private final FrameLayout bodyHost;

    private boolean expanded;
    private boolean streaming;
    private LinearLayout bodyColumn;
    private LinearLayout trailingColumn;

    public ReasoningShellView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        LinearLayout.LayoutParams shellLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 与上一条 part 留 4dp：一条消息里可能连续多个手风琴，贴在一起会看成同一块
        shellLp.topMargin = dimen(R.dimen.space_4);
        setLayoutParams(shellLp);

        // 折叠头 = 两行：上行（图标+文字+箭头），下行（收起态的"最后一步"摘要）。
        // 两行必须共用同一块底/同一个点击区，所以外层再包一个 box，chip/ripple 挂在 box 上。
        headerBox = new LinearLayout(context);
        headerBox.setOrientation(VERTICAL);
        headerBox.setBackgroundResource(R.drawable.chat_v2_think_pill);
        headerBox.setClickable(true);
        headerBox.setFocusable(true);
        headerBox.setClipToOutline(true);
        applyHeaderRipple(headerBox);
        addView(headerBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        headerRow = new LinearLayout(context);
        headerRow.setOrientation(HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setMinimumHeight(dimen(R.dimen.chat_v2_think_height));
        headerRow.setPadding(dimen(R.dimen.space_2), 0, dimen(R.dimen.space_8), 0);
        headerBox.addView(headerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView sparkle = new ImageView(context);
        sparkle.setImageResource(R.drawable.ic_chat_v2_sparkle);
        sparkle.setImageTintList(ColorStateList.valueOf(
                ThemeColors.attr(context, R.attr.colorPrimary)));
        sparkle.setContentDescription(context.getString(R.string.chat_v2_phase));
        LinearLayout.LayoutParams sparkleLp = new LinearLayout.LayoutParams(
                dimen(R.dimen.size_14), dimen(R.dimen.size_14));
        sparkleLp.setMarginStart(dimen(R.dimen.chat_v2_line_gap));
        headerRow.addView(sparkle, sparkleLp);

        dots = new LiveDotsView(context);
        dots.setDotCount(1);
        dots.setDotColor(ThemeColors.attr(context, R.attr.colorPrimary));
        dots.setVisibility(GONE);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsLp.setMarginStart(dimen(R.dimen.chat_v2_line_gap));
        headerRow.addView(dots, dotsLp);

        label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.chat_v2_think_text));
        label.setTextColor(ThemeColors.attr(context, R.attr.colorControlTextSecondary));
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.setMarginStart(dimen(R.dimen.chat_v2_line_gap));
        headerRow.addView(label, labelLp);

        chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setImageTintList(ColorStateList.valueOf(
                ThemeColors.attr(context, R.attr.colorControlTextHint)));
        chevron.setAlpha(0.55f);
        chevron.setContentDescription(null);
        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(
                dimen(R.dimen.size_13), dimen(R.dimen.size_13));
        chevronLp.setMarginStart(dimen(R.dimen.space_2));
        headerRow.addView(chevron, chevronLp);

        // 收起态第二行："最后：pwsh · Get-ChildItem…（已完成）"。
        // 与上行文字左对齐（2 + 7 + 14 + 7 = 30dp），11.5sp 次级色，单行省略。
        summary = new TextView(context);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.chat_v2_scene_sub_text));
        summary.setTextColor(ThemeColors.get(context, R.color.chat_v2_text_2));
        summary.setSingleLine(true);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        summary.setPadding(dimen(R.dimen.space_2) + dimen(R.dimen.chat_v2_line_gap)
                        + dimen(R.dimen.size_14) + dimen(R.dimen.chat_v2_line_gap),
                0, dimen(R.dimen.space_8), dimen(R.dimen.space_2));
        summary.setVisibility(GONE);
        headerBox.addView(summary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bodyHost = new FrameLayout(context);
        bodyHost.setBackgroundResource(R.drawable.chat_v2_think_body_bar);
        bodyHost.setPadding(dimen(R.dimen.space_12), 0, 0, dimen(R.dimen.space_8));
        bodyHost.setVisibility(GONE);
        addView(bodyHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 默认是收起态（历史消息、定稿后的思考），先落"卡片感"的底
        applyCollapsedChrome(true);
    }

    /** 折叠头的文字视图（渲染器在这里 setText / 挂流光）。 */
    public TextView label() {
        return label;
    }

    /** 正文容器（渲染器把 WebView / TextView 塞进来）。 */
    public ViewGroup bodyHost() {
        return bodyHost;
    }

    /** 点击折叠头（整行 + 收起态副标题）时回调。 */
    public void setOnHeaderClickListener(View.OnClickListener listener) {
        headerBox.setOnClickListener(listener);
    }

    /**
     * 收起态副标题（"最后：pwsh · …（已完成）"）。传 null/空串即隐藏。
     *
     * <p>只在**收起且已结束**时显示：展开时正文就在眼前、流式时头部已有活动文案，
     * 再放一行摘要会和正文/呼吸点抢注意力。</p>
     */
    public void setSummary(CharSequence text) {
        summary.setText(text == null ? "" : text);
        updateSummaryVisibility();
    }

    /** 当前副标题文本（测试/渲染器读回用）。 */
    public CharSequence summaryText() {
        return summary.getText();
    }

    /** 收起态副标题是否可见（测试用）。 */
    public boolean isSummaryVisible() {
        return summary.getVisibility() == VISIBLE;
    }

    /** 流式中：呼吸点出现 + 折叠头换"生成态"配色；定稿：回到静态次级色。 */
    public void setStreaming(boolean on) {
        if (streaming == on) {
            return;
        }
        streaming = on;
        dots.setVisibility(on ? VISIBLE : GONE);
        if (on) {
            dots.start();
        } else {
            dots.stop();
        }
        applyStateChrome(on);
        updateSummaryVisibility();
    }

    /**
     * 摘要只在"收起 + 有内容"时显示。
     *
     * <p>思考中用户主动收起时也要能看见"想到哪一步"（{@code ThinkingPreview} 口径），
     * 所以不再要求"已结束"；展开时正文就在眼前，摘要让位。</p>
     */
    private void updateSummaryVisibility() {
        boolean show = !expanded && summary.getText().length() > 0;
        summary.setVisibility(show ? VISIBLE : GONE);
    }

    /**
     * 有没有可展开的正文：没有就不显示箭头（否则给一个点了没反应的暗示）。
     * 用 INVISIBLE 保持右侧留白稳定，避免文字长度不同导致整行抖动。
     */
    public void setCollapsible(boolean collapsible) {
        chevron.setVisibility(collapsible ? VISIBLE : INVISIBLE);
    }

    /**
     * 生成态/结束态的视觉差（对标 AI Elements Reasoning：生成中高亮、结束回到静态）。
     *
     * <p>生成中：文字走主题主色、整行加一层 6% 主色圆角底——它和旁边的静态卡片明显不是一类；
     * 结束：文字回到次级色、底色撤掉，安静地待在正文上方。只改配色不动布局，切换不跳。</p>
     */
    private void applyStateChrome(boolean generating) {
        int accent = ThemeColors.attr(getContext(), R.attr.colorPrimary);
        label.setTextColor(generating
                ? accent
                : ThemeColors.attr(getContext(), R.attr.colorControlTextSecondary));
        if (generating) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            int alpha = Math.round(0.06f * 255f);
            bg.setColor((accent & 0x00FFFFFF) | (alpha << 24));
            bg.setCornerRadius(dimen(R.dimen.chat_v2_line_pad));
            headerBox.setBackground(bg);
        } else {
            headerBox.setBackgroundResource(R.drawable.chat_v2_think_pill);
        }
    }

    /** 正文视图由渲染器塞入；重复调用只替换第一个子视图。 */
    public void setBodyView(View body) {
        bodyColumn = null; // 单正文模式与正文列互斥，别让旧列引用悬空
        bodyHost.removeAllViews();
        if (body != null) {
            bodyHost.addView(body, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /**
     * 正文列（思考手风琴用）：多个成员（思考段 / 工具行）按顺序竖排在同一个折叠体内。
     * 首次调用时创建，之后返回同一个实例；渲染器每次重绑自行 clear 再填。
     */
    public LinearLayout bodyColumn() {
        if (bodyColumn == null) {
            bodyColumn = new LinearLayout(getContext());
            bodyColumn.setOrientation(VERTICAL);
            bodyHost.addView(bodyColumn, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return bodyColumn;
    }

    /**
     * 折叠体<b>之外</b>的常显区（产物卡用）：折叠时不跟着藏——图片/图表/文件卡必须一直可见。
     */
    public LinearLayout trailingColumn() {
        if (trailingColumn == null) {
            trailingColumn = new LinearLayout(getContext());
            trailingColumn.setOrientation(VERTICAL);
            addView(trailingColumn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return trailingColumn;
    }

    /**
     * 展开/收起。只在状态真的变化时动（流式重绑不会反复播）。
     *
     * @param expand  目标态
     * @param animate 是否播高度动画（绑定时传 false，用户点击/定稿收起传 true）
     */
    public void setExpanded(boolean expand, boolean animate) {
        boolean visible = bodyHost.getVisibility() == VISIBLE;
        if (expanded == expand && visible == expand) {
            return;
        }
        expanded = expand;
        UiFx.rotateChevron(chevron, expand);
        applyCollapsedChrome(!expand);
        updateSummaryVisibility();
        if (!animate || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            bodyHost.setVisibility(expand ? VISIBLE : GONE);
            resetHostHeight();
            return;
        }
        if (expand) {
            bodyHost.setVisibility(VISIBLE);
            int target = measureBodyHeight();
            if (target <= 0) {
                resetHostHeight();
                return;
            }
            UiFx.animateHeight(bodyHost, 0, target, EXPAND_MS, this::resetHostHeight);
        } else {
            int from = bodyHost.getHeight();
            if (from <= 0) {
                bodyHost.setVisibility(GONE);
                resetHostHeight();
                return;
            }
            UiFx.animateHeight(bodyHost, from, 0, COLLAPSE_MS, () -> {
                bodyHost.setVisibility(GONE);
                resetHostHeight();
            });
        }
    }

    /**
     * 收起态给折叠头一块"卡片感"的底（2026-11-18）。
     *
     * <p>之前收起后就是一行悬空的文字，一条消息里连着好几个手风琴时看不出"这是几组"；
     * 收起态落一块 {@code colorSurfaceVariant} 圆角底，展开态撤掉、让正文的左侧引导线接管，
     * 两种形态一眼能分开，也和消息里的其它卡片同一套表面层级。</p>
     */
    private void applyCollapsedChrome(boolean collapsed) {
        if (!collapsed) {
            headerBox.setBackgroundResource(R.drawable.chat_v2_think_pill);
            return;
        }
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(ThemeColors.attr(getContext(), R.attr.colorControlBackground));
        bg.setCornerRadius(dimen(R.dimen.chat_v2_scene_radius));
        headerBox.setBackground(bg);
    }

    /** 当前是否展开（测试/渲染器读回用）。 */
    public boolean isExpanded() {
        return expanded;
    }

    /** 正文自然高度：优先用已测量值，未测量（如 WebView 刚建）时按宽度量一次。 */
    private int measureBodyHeight() {
        View body = bodyHost.getChildAt(0);
        if (body == null) {
            return 0;
        }
        int width = bodyHost.getWidth();
        if (width <= 0) {
            width = getWidth();
        }
        if (width <= 0) {
            return 0;
        }
        int inner = Math.max(0, width - bodyHost.getPaddingStart() - bodyHost.getPaddingEnd());
        body.measure(View.MeasureSpec.makeMeasureSpec(inner, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int measured = body.getMeasuredHeight();
        if (measured <= 0) {
            return 0;
        }
        return measured + bodyHost.getPaddingTop() + bodyHost.getPaddingBottom();
    }

    /** 动画结束后把容器高度放回 WRAP_CONTENT，交给正常测量（内容后来变化也能自己长）。 */
    private void resetHostHeight() {
        ViewGroup.LayoutParams lp = bodyHost.getLayoutParams();
        if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            bodyHost.setLayoutParams(lp);
        }
    }

    private void applyHeaderRipple(View view) {
        TypedValue tv = new TypedValue();
        if (getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true)) {
            view.setForeground(ContextCompat.getDrawable(getContext(), tv.resourceId));
        }
    }

    private int dimen(int resId) {
        return getResources().getDimensionPixelSize(resId);
    }

    /** 便于调试：当前的呼吸点（测试断言"流式才有"）。 */
    public View dotsView() {
        return dots;
    }

    @SuppressWarnings("unused")
    private static Typeface unused() {
        return Typeface.DEFAULT;
    }
}
