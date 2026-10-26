package com.oilquiz.app.ai.chat.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.oilquiz.app.R;

/**
 * 聊天页 v2 外壳（Track A0）：一屏只留 顶栏 / 对话流 / 输入胶囊 三层。
 *
 * <p>做法是<b>运行时改造现有布局</b>而不是重写 {@code activity_ai_chat.xml}：
 * 把技术信息行（服务状态条、远程 DSH 连接条、统计条里的 token 段、日志面板）收起来，
 * 插一条只讲"在和哪个模型说话"的顶栏，技术细节改由右上「⋯」的 BottomSheet 承载。
 * （2026-10-20 S5：v1 已删除，本类不再有开关，进页面即改造。）
 *
 * <p>数据来源不另起一套：被收起的那几个 TextView 仍由原有逻辑刷新，
 * 打开面板时直接读它们的当前值，避免在 {@code AIChatActivity} 里再插一遍更新代码。
 *
 * <p>本类任何异常都不允许影响聊天页，{@link #apply} 整体兜底。
 */
public final class ChatUiV2Chrome {

    private static final String TAG = "ChatUiV2Chrome";

    /** 顶栏插入标记：重复 apply 时不再插第二条 */
    private static final int TAG_HEADER = 0x7a000001;

    /** 副标题（模型/服务状态）标记，供 {@link #updateSubtitle} 定位 */
    private static final int TAG_SUBTITLE = 0x7a000002;

    private ChatUiV2Chrome() {
    }

    /**
     * 同步 v2 顶栏副标题。
     *
     * <p>顶栏的模型文案是<b>复制</b>自旧状态条的当前值，旧状态条被隐藏后仍由
     * {@code setStatusTextIfChanged} 刷新，所以必须在这里跟着更新，
     * 否则切模型/服务初始化完成后顶栏会一直停在冷启动那一刻的"AI服务未初始化"。
     */
    public static void updateSubtitle(Activity activity, String text) {
        setText(activity, R.id.chat_v2_subtitle, TAG_SUBTITLE, text);
    }

    /**
     * 回聊天页时重算一次顶栏副标题（2026-10-25，模型入口改跳「模型选择」页之后需要它）。
     *
     * <p>顶栏副标题是<b>复制</b>自旧状态条的当前值，而 {@code OnlineModelManager} 的
     * "激活模型变更"虽然定义了 {@code ModelChangeListener} 机制，全仓库却<b>没有任何注册方</b>
     * （{@code notifyActiveChanged} 只会遍历空的 listener 列表），
     * 所以在模型中心切完在线模型返回聊天页时，顶栏会一直停在离开那一刻。
     * 由 {@code AIChatActivity.onResume} 调这里补刷一次；v2 顶栏没挂上时静默跳过。</p>
     */
    public static void refreshSubtitleIfNeeded(Activity activity) {
        if (!isApplied(activity)) return;
        try {
            updateSubtitle(activity, modelSubtitle(activity));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 顶栏状态点颜色（demo 的 .s .dot）：就绪绿、错误红、加载中品牌色、未知灰。
     * 与副标题文案同源，由 {@code ServiceStatusManager} 在写状态时一起调用。
     */
    public static void updateStatusDot(Activity activity, int colorRes) {
        if (activity == null) return;
        View dot = activity.findViewById(R.id.chat_v2_status_dot);
        if (dot == null) return;
        try {
            dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(activity, colorRes)));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 同步 v2 顶栏主标题（会话标题）。开关关着或还没插顶栏时静默忽略，
     * 旧版头部没有标题位，因此这里不回落写任何控件。
     */
    public static void updateTitle(Activity activity, String text) {
        setText(activity, R.id.chat_v2_title, 0, text);
    }

    private static void setText(Activity activity, int id, int fallbackTag, String text) {
        if (activity == null || TextUtils.isEmpty(text)) return;
        try {
            ViewGroup root = activity.findViewById(R.id.main_content);
            if (root == null) return;
            View header = root.findViewWithTag(TAG_HEADER);
            if (header == null) return;
            View target = header.findViewById(id);
            if (target == null && fallbackTag != 0) target = header.findViewWithTag(fallbackTag);
            if (target instanceof TextView) {
                ((TextView) target).setText(text);
            }
        } catch (Throwable ignored) {
            // 顶栏同步失败不影响聊天页
        }
    }

    /** 改造外壳（2026-10-20 S5 起无开关，进页面即生效） */
    public static void apply(Activity activity) {
        if (activity == null) return;
        try {
            doApply(activity);
        } catch (Throwable t) {
            // 外壳改造失败只影响视觉，绝不能让聊天页起不来
            Log.w(TAG, "apply skipped: " + t.getMessage());
        }
    }

    private static void doApply(Activity activity) {
        // 旧状态胶囊（模型 · 余额 · token · 上下文 · KV）收起：数据仍由原逻辑刷新，
        // 「⋯」面板读同一批 TextView；顶部只保留顶栏副标题的"模型 · AI 服务状态"。
        hide(activity, R.id.service_status_bar);
        View statusBar = activity.findViewById(R.id.service_status_bar);
        if (statusBar != null && statusBar.getParent() instanceof View) {
            ((View) statusBar.getParent()).setVisibility(View.GONE);
        }
        hide(activity, R.id.btn_back);
        hide(activity, R.id.remote_dsh_bar);
        // demo 没有左下角历史悬浮球：v2 的历史入口就是顶栏汉堡键，悬浮球会盖住正文
        hide(activity, R.id.btn_history_fab);
        // v1 的"思考中"金色渐变细线（3dp）：生成状态已挪到输入区上方那一行，
        // 这条线留在列表底部只会像一条脏边（2026-10-25 用户反馈"丑"）。
        hide(activity, R.id.thinking_indicator);
        // 会话统计胶囊条（token/耗时/轮次/上下文仪表）：demo 的输入区上方只有模式 chip，
        // 这些技术读数在「⋯」面板里都有，v2 下把整条连同它下面的分隔线一起收起。
        // 数据源不动：ChatStatsBar 仍被原逻辑刷新，面板打开时照旧读它的值。
        View statsBar = activity.findViewById(R.id.session_stats_bar);
        if (statsBar != null) {
            if (statsBar instanceof ChatStatsBar) {
                // 不能用 setVisibility：ChatStatsBar.update() 每次刷新都会把自己显回来
                ((ChatStatsBar) statsBar).setSuppressed(true);
            } else {
                statsBar.setVisibility(View.GONE);
            }
            if (statsBar.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) statsBar.getParent();
                int index = parent.indexOfChild(statsBar);
                View divider = index + 1 < parent.getChildCount() ? parent.getChildAt(index + 1) : null;
                if (divider != null && divider.getLayoutParams() != null
                        && divider.getLayoutParams().height <= dp(activity, 2)) {
                    divider.setVisibility(View.GONE);
                }
            }
        }
        compressStatsBar(activity);

        ViewGroup root = activity.findViewById(R.id.main_content);
        if (root == null) return;
        // 页面底色：demo 的整套 token（surface/border/text）都是按"浅底/深底纯色"设计的，
        // 坐在首页那张深蓝壁纸上会全部失配（深色字压深底）。v2 用 chat_v2_bg 把内容层铺成纯色，
        // 壁纸留给抽屉本身（history_drawer / tool drawer 仍按原样）。
        root.setBackgroundColor(ContextCompat.getColor(activity, R.color.chat_v2_bg));
        if (root.findViewWithTag(TAG_HEADER) != null) return;

        View header = buildHeader(activity, root);
        header.setTag(TAG_HEADER);
        root.addView(header, 0);
    }

    /**
     * v2 外壳是否已经应用（用于抑制 v1 的"思考中"细线等旧指示器）。
     */
    public static boolean isApplied(Activity activity) {
        if (activity == null) return false;
        try {
            ViewGroup root = activity.findViewById(R.id.main_content);
            return root != null && root.findViewWithTag(TAG_HEADER) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 顶栏

    /**
     * 顶栏来自 {@code view_chat_appbar_v2}（demo header.appbar）：
     * 历史抽屉 / 会话标题 + 模型副标题 / 新建 / 更多。
     *
     * <p>入口去向（2026-10-25 收敛）：历史 = 转发 {@code btn_history}，
     * 新建 = 转发 {@code btn_new_conversation}，「⋯」= 转发 {@code btn_back} 打开工具中心
     * （原先它弹自制的信息面板，面板已删、状态行搬进抽屉），
     * 模型 = 直跳 {@code ModelCenterActivity}（原先弹 ModelSheet，那是个不接线的半成品）。
     * 被转发的这些控件在 v2 下虽被隐藏，{@code performClick} 依然有效。</p>
     */
    private static View buildHeader(Activity activity, ViewGroup parent) {
        Context ctx = activity;
        View header = LayoutInflater.from(ctx).inflate(R.layout.view_chat_appbar_v2, parent, false);

        TextView subtitle = header.findViewById(R.id.chat_v2_subtitle);
        if (subtitle != null) {
            subtitle.setText(modelSubtitle(ctx));
            subtitle.setTag(TAG_SUBTITLE);
        }

        click(header, R.id.chat_v2_btn_history, v -> perform(activity, R.id.btn_history));
        click(header, R.id.chat_v2_btn_new, v -> perform(activity, R.id.btn_new_conversation));
        // 「⋯」不再弹自制的信息面板（已删）：状态与运行日志都搬进了工具中心，
        // 这里复用旧汉堡键的点击逻辑打开工具抽屉，不另写一套打开流程。
        click(header, R.id.chat_v2_btn_more, v -> perform(activity, R.id.btn_back));
        // 模型入口直跳「模型选择」页（2026-10-25）：原先这里弹的是聊天页内的 ModelSheet 面板，
        // 但它本地 tab 的行根本没挂点击、"应用"也只写温度不切模型，却照样弹"已应用"——
        // 是个会骗人的半成品。切换模型/下载/删除的统一入口本来就在 ModelCenterActivity，
        // 不再在聊天页复制一份"看着能切、其实不接线"的 UI。
        click(header, R.id.chat_v2_btn_model, v ->
                activity.startActivity(new Intent(activity,
                        com.oilquiz.app.ui.activity.ModelCenterActivity.class)));
        return header;
    }

    /** 给 v2 顶栏上的一个控件挂点击回调；控件缺失时静默跳过（不影响其余入口） */
    private static void click(View root, int id, View.OnClickListener listener) {
        View v = root.findViewById(id);
        if (v != null) v.setOnClickListener(listener);
    }

    /** 转发点击到页面上已存在的旧控件；返回 false 表示该控件不存在，调用方自行兜底 */
    private static boolean perform(Activity activity, int id) {
        View v = activity.findViewById(id);
        if (v == null) return false;
        v.performClick();
        return true;
    }

    /** 副标题：优先服务状态条上的模型文案（原逻辑一直在刷新它） */
    private static String modelSubtitle(Context ctx) {
        TextView status = find(ctx, R.id.service_status_text);
        if (status != null && status.getText() != null && status.getText().length() > 0) {
            return status.getText().toString();
        }
        return ctx.getString(R.string.chat_v2_no_data);
    }

    // ---------------------------------------------------------------- 输入区密度

    /**
     * 输入区下方的模式 chip（demo {@code .mode}）：28dp、无描边、未选中透明底，
     * 选中 brand-soft 底 + brand-ink 字/图标。这里是<b>唯一</b>上色点；
     * {@code AIChatActivity} 的三个 updateXxxChip 只负责 checked 与文案，不再覆盖颜色。
     */
    private static void compressStatsBar(Activity activity) {
        int[] chips = {R.id.chip_deep_think, R.id.chip_web_search, R.id.chip_local_agent};
        for (int id : chips) {
            View chip = activity.findViewById(id);
            if (chip == null) continue;
            ViewGroup.LayoutParams lp = chip.getLayoutParams();
            int h = dp(activity, 28);
            if (lp != null && lp.height != h) {
                lp.height = h;
                chip.setLayoutParams(lp);
            }
            chip.setMinimumHeight(h);
            if (chip instanceof TextView) {
                ((TextView) chip).setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            }
            styleModeChip(chip);
        }
    }

    /**
     * 只改样式，不动 chip 的交互（checked 仍由原有 setChecked 逻辑驱动）。
     * 图标 tint 也走同一组状态：选中 brand-ink，未选中 text-3。
     */
    private static void styleModeChip(View chip) {
        if (!(chip instanceof com.google.android.material.chip.Chip)) return;
        com.google.android.material.chip.Chip c = (com.google.android.material.chip.Chip) chip;
        Context ctx = c.getContext();
        int soft = ContextCompat.getColor(ctx, R.color.chat_v2_brand_soft);
        int ink = ContextCompat.getColor(ctx, R.color.chat_v2_brand_ink);
        int idleText = ContextCompat.getColor(ctx, R.color.chat_v2_text_2);
        int idleIcon = ContextCompat.getColor(ctx, R.color.chat_v2_text_3);
        int transparent = android.graphics.Color.TRANSPARENT;
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked},
                new int[]{},
        };
        c.setChipBackgroundColor(new android.content.res.ColorStateList(states,
                new int[]{soft, transparent, transparent}));
        c.setTextColor(new android.content.res.ColorStateList(states,
                new int[]{ink, idleText, idleText}));
        c.setChipIconTint(new android.content.res.ColorStateList(states,
                new int[]{ink, idleIcon, idleIcon}));
        c.setChipStrokeWidth(0f);
        c.setCheckedIconVisible(false);
        // 默认 48dp 触控热区会把 28dp 的行撑高；这里只保留视觉高度（点击热区仍由父容器兜底）
        c.setEnsureMinTouchTargetSize(false);
        c.setChipStartPadding(dp(ctx, 8));
        c.setChipEndPadding(dp(ctx, 10));
        c.setIconStartPadding(dp(ctx, 2));
        c.setIconEndPadding(dp(ctx, 4));
        c.setTextStartPadding(dp(ctx, 2));
        c.setTextEndPadding(dp(ctx, 2));
    }

    // ---------------------------------------------------------------- 小工具

    private static void hide(Activity activity, int id) {
        View v = activity.findViewById(id);
        if (v != null) v.setVisibility(View.GONE);
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T find(Context ctx, int id) {
        if (!(ctx instanceof Activity)) return null;
        try {
            return (T) ((Activity) ctx).findViewById(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int dp(Context ctx, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }
}
