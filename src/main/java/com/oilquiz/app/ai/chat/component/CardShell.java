package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.widget.ImageViewCompat;

import com.oilquiz.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一卡片外壳（Track B0-2）。
 *
 * <p>解决的问题：30 个组件各自 {@code new GradientDrawable()} 画圆角/描边/内边距，
 * 标题字重和按钮样式每人一套，动作按钮还被外层包在卡片背景之外（视觉上"飘"在消息流里）。
 *
 * <p>结构与设计基线（{@code docs/ui/component-system-review.md} §3.1，
 * 与 {@code demo/ai-chat-redesign.html} 的卡片一致）：
 * <pre>
 * ┌──────────────────────────────┐
 * │ [icon] 标题             meta │  header：13sp/600 + 11sp meta
 * │ 副标题                       │
 * ├──────────────────────────────┤
 * │ body（组件自己填）            │  13–14sp；可开内部滚动（上限 360dp）
 * │ 空态 / 加载 / 失败 / 截断     │  notice 行
 * ├──────────────────────────────┤
 * │ [主操作] [次操作]      [更多] │  footer：最多 {@link ComponentTheme#MAX_INLINE_ACTIONS} 个
 * └──────────────────────────────┘
 * 容器：surface + 1px 描边 + 14dp 圆角 + 14dp 内边距
 * </pre>
 *
 * <p>组件只负责往 {@link #getBody()} 里塞内容，颜色与尺寸一律走 {@link ComponentTheme}。
 */
public class CardShell extends LinearLayout {

    /** 卡片状态位：决定 notice 行的文案与配色 */
    public enum State { NORMAL, LOADING, EMPTY, ERROR, TRUNCATED }

    private final Context shellContext;
    private final LinearLayout header;
    private final ImageView iconView;
    private final TextView titleView;
    private final TextView metaView;
    private final TextView subtitleView;
    private final TextView noticeView;
    private final LinearLayout footer;
    private final LinearLayout footerInline;
    private final TextView moreButton;

    /** footer 溢出的动作（进「更多」面板） */
    private final List<Action> overflowActions = new ArrayList<>();
    private final boolean canUseDialog;

    private LinearLayout body;
    private View bodyAnchor;
    private boolean bodyScrollWrapped;

    private CardShell(Context context, boolean canUseDialog) {
        super(context);
        this.shellContext = context;
        this.canUseDialog = canUseDialog;
        setOrientation(VERTICAL);
        setPadding(ComponentTheme.padding(context), ComponentTheme.padding(context),
                ComponentTheme.padding(context), ComponentTheme.padding(context));
        setBackground(ComponentTheme.cardBackground(context));
        applyRipple();

        header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setVisibility(GONE);

        iconView = new ImageView(context);
        int iconSize = ComponentTheme.dp(context, 16);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMarginEnd(ComponentTheme.dp(context, 6));
        iconView.setLayoutParams(iconLp);
        iconView.setVisibility(GONE);

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ComponentTheme.headerTextSize());
        titleView.setTextColor(ComponentTheme.textPrimary(context));
        titleView.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));

        metaView = new TextView(context);
        metaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ComponentTheme.metaTextSize());
        metaView.setTextColor(ComponentTheme.textSecondary(context));
        metaView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        metaView.setVisibility(GONE);

        header.addView(iconView);
        header.addView(titleView);
        header.addView(metaView);
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ComponentTheme.metaTextSize());
        subtitleView.setTextColor(ComponentTheme.textSecondary(context));
        subtitleView.setVisibility(GONE);
        addView(subtitleView, rowLp(ComponentTheme.dp(context, 2), 0));

        // footer 先建，body 后建时插到 footer 之前，保证「内容在上、动作在下」
        footer = new LinearLayout(context);
        footer.setOrientation(HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setVisibility(GONE);

        footerInline = new LinearLayout(context);
        footerInline.setOrientation(HORIZONTAL);
        footerInline.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        footerInline.setLayoutParams(new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));

        moreButton = new TextView(context);
        moreButton.setText(getContext().getString(R.string.card_shell_more));
        moreButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, ComponentTheme.actionTextSize());
        moreButton.setTextColor(ComponentTheme.brand(context));
        moreButton.setPadding(ComponentTheme.dp(context, 8), ComponentTheme.dp(context, 4),
                ComponentTheme.dp(context, 8), ComponentTheme.dp(context, 4));
        moreButton.setBackground(ComponentTheme.chipBackground(context, false));
        moreButton.setVisibility(GONE);
        moreButton.setOnClickListener(v -> showOverflowDialog());

        footer.addView(footerInline);
        footer.addView(moreButton);

        noticeView = new TextView(context);
        noticeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ComponentTheme.metaTextSize());
        noticeView.setVisibility(GONE);
        addView(noticeView, rowLp(ComponentTheme.dp(context, 4), 0));

        // footer 挂在最后：body 通过 indexOfChild(noticeView) 插到它前面，动作永远在卡片底部
        addView(footer, rowLp(ComponentTheme.gap(context), 0));
    }

    /**
     * 创建卡片外壳。
     *
     * @param context 渲染上下文；非 Activity 时「更多」面板无处弹窗，溢出动作会直接并排显示
     */
    public static CardShell create(Context context) {
        return new CardShell(context, context instanceof android.app.Activity);
    }

    // ---------------------------------------------------------------- header

    /** 设置标题（首次调用时展开 header 行） */
    public CardShell setTitle(String title) {
        if (!TextUtils.isEmpty(title)) {
            titleView.setText(title);
            header.setVisibility(VISIBLE);
            setBodyTopGap(true);
        }
        return this;
    }

    /** 标题 + 右侧 meta（如"3 项""12.6 MB""1.2s"）；只给 meta 也要显示出来 */
    public CardShell setHeader(String title, String meta) {
        setTitle(title);
        if (!TextUtils.isEmpty(meta)) {
            metaView.setText(meta);
            metaView.setVisibility(VISIBLE);
            header.setVisibility(VISIBLE);
            setBodyTopGap(true);
        }
        return this;
    }

    /** 标题左侧矢量图标（不接受 emoji，图标一律 res/drawable/ic_*.xml），默认品牌色 */
    public CardShell setHeaderIcon(int drawableRes) {
        return setHeaderIcon(drawableRes, ComponentTheme.brand(shellContext));
    }

    /**
     * 标题左侧矢量图标 + 指定着色（token 色，不接受任意十六进制）。
     *
     * <p>file_card 这类"按类型换图标"的卡片需要颜色也带语义（pdf 危险色、表格成功色…），
     * 但颜色仍只能从 {@link ComponentTheme} 取，避免组件又各自写死色值。
     */
    public CardShell setHeaderIcon(int drawableRes, int tintColor) {
        if (drawableRes != 0) {
            iconView.setImageResource(drawableRes);
            ImageViewCompat.setImageTintList(iconView, ColorStateList.valueOf(tintColor));
            iconView.setVisibility(VISIBLE);
        }
        return this;
    }

    /** 标题下方副标题 */
    public CardShell setSubtitle(String subtitle) {
        if (!TextUtils.isEmpty(subtitle)) {
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(VISIBLE);
            setBodyTopGap(true);
        }
        return this;
    }

    // ---------------------------------------------------------------- body

    /** 组件内容容器：所有正文控件加到这里 */
    public LinearLayout getBody() {
        ensureBody();
        return body;
    }

    /** 追加一个内容控件（保持宽度撑满，weight 分配才生效） */
    public CardShell addBody(View view) {
        if (view == null) return this;
        ensureBody();
        body.addView(view, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return this;
    }

    /** 追加一个自带尺寸的内容控件（如图表这种需要固定高度的自绘 View） */
    public CardShell addBody(View view, LinearLayout.LayoutParams params) {
        if (view == null) return this;
        ensureBody();
        body.addView(view, params);
        return this;
    }

    /** 清空 body（组件内导航/刷新时复用同一张卡，不重建外壳） */
    public CardShell clearBody() {
        ensureBody();
        body.removeAllViews();
        return this;
    }

    /** 追加一段正文文本 */
    public TextView addBodyText(String text, float sizeSp, int color, boolean medium) {
        TextView tv = new TextView(shellContext);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        tv.setLineSpacing(0, 1.3f);
        if (medium) {
            tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
        }
        addBody(tv);
        return tv;
    }

    /**
     * 让内容区可内部滚动（上限 {@link ComponentTheme#bodyMaxHeight}）。
     * 需在往 body 加内容之前调用；重复调用无效。
     *
     * <p>两个约束决定了它的使用范围：
     * <ul>
     *   <li>WebView 类组件不要用——它自带滚动，再包一层会和页内滚动抢手势；</li>
     *   <li>纵向滚动会与聊天列表的手势冲突，所以像 table_card 这种"内容可能很长"的
     *       卡片改用截断态（{@link State#TRUNCATED}）而不是内滚。</li>
     * </ul>
     * 目前留给 Track B1 的 DynamicCardView（未知类型兜底，最长的一类）。
     */
    public CardShell enableBodyScroll() {
        ensureBody();
        if (bodyScrollWrapped) return this;
        removeView(bodyAnchor);
        MaxHeightScrollView scroller = new MaxHeightScrollView(shellContext,
                ComponentTheme.bodyMaxHeight(shellContext));
        scroller.addView(body, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        bodyAnchor = scroller;
        boolean hasAbove = header.getVisibility() == VISIBLE
                || subtitleView.getVisibility() == VISIBLE;
        addView(scroller, indexOfChild(noticeView),
                rowLp(hasAbove ? ComponentTheme.gap(shellContext) : 0, 0));
        bodyScrollWrapped = true;
        return this;
    }

    private void ensureBody() {
        if (body != null) return;
        body = new LinearLayout(shellContext);
        body.setOrientation(VERTICAL);
        bodyAnchor = body;
        // 顶部间距按"上面有没有东西"决定：header/副标题可见才留 gap，
        // 否则卡片里会凭空多出一段空白
        boolean hasAbove = header.getVisibility() == VISIBLE
                || subtitleView.getVisibility() == VISIBLE;
        addView(body, indexOfChild(noticeView),
                rowLp(hasAbove ? ComponentTheme.gap(shellContext) : 0, 0));
    }

    private void setBodyTopGap(boolean withGap) {
        if (bodyAnchor == null || !withGap) return;
        LayoutParams lp = (LayoutParams) bodyAnchor.getLayoutParams();
        if (lp != null) {
            lp.topMargin = ComponentTheme.gap(shellContext);
            bodyAnchor.setLayoutParams(lp);
        }
    }

    // ---------------------------------------------------------------- state

    /**
     * 设置卡片状态：在 body 与 footer 之间显示一行说明。
     *
     * @param message 为空时用该状态的默认文案（走 strings.xml，不写死在代码里）
     */
    public CardShell setState(State state, String message) {
        if (state == null || state == State.NORMAL) {
            noticeView.setVisibility(GONE);
            return this;
        }
        noticeView.setText(message != null ? message : defaultStateText(state));
        noticeView.setTextColor(stateColor(state));
        noticeView.setVisibility(VISIBLE);
        return this;
    }

    public CardShell setState(State state) {
        return setState(state, null);
    }

    private String defaultStateText(State state) {
        switch (state) {
            case LOADING:
                return getContext().getString(R.string.card_shell_loading);
            case EMPTY:
                return getContext().getString(R.string.card_shell_empty);
            case TRUNCATED:
                return getContext().getString(R.string.card_shell_truncated);
            case ERROR:
            default:
                return getContext().getString(R.string.card_shell_error);
        }
    }

    private int stateColor(State state) {
        Context c = shellContext;
        switch (state) {
            case LOADING:
                return ComponentTheme.textSecondary(c);
            case ERROR:
                return ComponentTheme.danger(c);
            case TRUNCATED:
            case EMPTY:
            default:
                return ComponentTheme.warning(c);
        }
    }

    // ---------------------------------------------------------------- footer

    /** footer 动作 */
    public static final class Action {
        public final String label;
        public final boolean primary;
        public final Runnable onClick;

        public Action(String label, boolean primary, Runnable onClick) {
            this.label = label;
            this.primary = primary;
            this.onClick = onClick;
        }
    }

    /**
     * 追加一个 footer 动作：前 {@link ComponentTheme#MAX_INLINE_ACTIONS} 个并排显示，
     * 之后的收进「更多」面板（非 Activity 上下文无法弹面板，仍并排显示）。
     *
     * <p>口径来自设计基线：卡片 footer 最多 2 个动作 + 更多，避免 Agent 给十几个
     * actions 时把卡片撑成一屏按钮。
     */
    public CardShell addAction(String label, boolean primary, Runnable onClick) {
        if (TextUtils.isEmpty(label) || onClick == null) return this;
        Action action = new Action(label, primary, onClick);
        if (footerInline.getChildCount() < ComponentTheme.MAX_INLINE_ACTIONS || !canUseDialog) {
            footerInline.addView(buildActionButton(action));
        } else {
            overflowActions.add(action);
            moreButton.setText(getContext().getString(R.string.card_shell_more)
                    + " (" + overflowActions.size() + ")");
            moreButton.setVisibility(VISIBLE);
        }
        footer.setVisibility(VISIBLE);
        return this;
    }

    /** 从 props.actions 批量追加（与 {@link ComponentActions} 的执行链路共用一套语义） */
    public CardShell addActions(JSONArray actions) {
        if (actions == null) return this;
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.optJSONObject(i);
            if (a == null) continue;
            Runnable run = ComponentActions.actionRunner(shellContext, a);
            if (run == null) continue;
            String label = a.optString("label", "");
            addAction(TextUtils.isEmpty(label) ? getContext().getString(R.string.card_shell_more)
                    : label, i == 0, run);
        }
        return this;
    }

    /**
     * 清空 footer 动作（B1：题目卡提交后动作要从「提交」换成「重做」）。
     * 清空后 footer 隐藏，调用方再 {@link #addAction} 重新填。
     */
    public CardShell clearActions() {
        footerInline.removeAllViews();
        overflowActions.clear();
        moreButton.setVisibility(GONE);
        footer.setVisibility(GONE);
        return this;
    }

    private TextView buildActionButton(Action action) {
        TextView btn = new TextView(shellContext);
        btn.setText(action.label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, ComponentTheme.actionTextSize());
        btn.setTextColor(action.primary ? ComponentTheme.brand(shellContext)
                : ComponentTheme.textSecondary(shellContext));
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setMinHeight(ComponentTheme.dp(shellContext, 32));
        btn.setPadding(ComponentTheme.dp(shellContext, 2), ComponentTheme.dp(shellContext, 6),
                ComponentTheme.dp(shellContext, 10), ComponentTheme.dp(shellContext, 6));
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(v -> {
            try {
                action.onClick.run();
            } catch (Throwable ignored) {
                // 动作失败不影响卡片本身
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(ComponentTheme.dp(shellContext, 4));
        btn.setLayoutParams(lp);
        return btn;
    }

    private void showOverflowDialog() {
        if (overflowActions.isEmpty() || !canUseDialog) return;
        String[] labels = new String[overflowActions.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = overflowActions.get(i).label;
        }
        new androidx.appcompat.app.AlertDialog.Builder(shellContext)
                .setTitle(getContext().getString(R.string.card_shell_actions_more,
                        overflowActions.size()))
                .setItems(labels, (dialog, which) -> {
                    try {
                        overflowActions.get(which).onClick.run();
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- 杂项

    /** 点击水波：卡片本身可点时给出反馈（此前各卡各凭 TextView 自己变色） */
    private void applyRipple() {
        android.content.res.TypedArray ta = shellContext.getTheme().obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground});
        try {
            android.graphics.drawable.Drawable ripple = ta.getDrawable(0);
            if (ripple != null) setForeground(ripple);
        } catch (Throwable ignored) {
            // 拿不到水波不影响卡片可用性
        } finally {
            ta.recycle();
        }
    }

    /** 让卡片可点击（如「▶ 打开固定画布」这类整卡入口） */
    public CardShell setCardClickable(Runnable onClick) {
        if (onClick == null) return this;
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> {
            try {
                onClick.run();
            } catch (Throwable ignored) {
            }
        });
        return this;
    }

    private LayoutParams rowLp(int topMargin, int bottomMargin) {
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        lp.bottomMargin = bottomMargin;
        return lp;
    }

    /**
     * 带最大高度的滚动容器：内容不足时按内容高，超过上限才滚动。
     * 公开给需要"局部限高"的组件（如 DynamicCardView 的原始 JSON 折叠区）。
     */
    public static class MaxHeightScrollView extends ScrollView {
        private final int maxHeightPx;

        MaxHeightScrollView(Context context, int maxHeightPx) {
            super(context);
            this.maxHeightPx = maxHeightPx;
            setVerticalScrollBarEnabled(true);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            // 只改测量结果，不再二次 onMeasure（避免自触发 requestLayout 循环）
            if (maxHeightPx > 0 && getMeasuredHeight() > maxHeightPx) {
                setMeasuredDimension(getMeasuredWidth(), maxHeightPx);
            }
        }
    }
}
