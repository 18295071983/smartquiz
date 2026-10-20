package com.oilquiz.app.ai.chat.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;

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
        hide(activity, R.id.service_status_bar);
        hide(activity, R.id.remote_dsh_bar);
        hide(activity, R.id.log_container);
        // 旧头部胶囊（状态信息 + 右侧「工具中心」抽屉按钮）是同一行的两个子控件，
        // 真机上只隐藏状态条会留下一条空白胶囊 + 一个孤零零的汉堡键（2026-10-19 实测），
        // 所以整行收起；工具中心改由「⋯」面板提供。
        View statusBar = activity.findViewById(R.id.service_status_bar);
        if (statusBar != null && statusBar.getParent() instanceof View) {
            ((View) statusBar.getParent()).setVisibility(View.GONE);
        }
        hide(activity, R.id.btn_back);
        // demo 没有左下角历史悬浮球：v2 的历史入口就是顶栏汉堡键，悬浮球会盖住正文
        hide(activity, R.id.btn_history_fab);
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

    // ---------------------------------------------------------------- 顶栏

    /**
     * 顶栏来自 {@code view_chat_appbar_v2}（demo header.appbar）：
     * 历史抽屉 / 会话标题 + 模型副标题 / 新建 / 更多。
     *
     * <p>三个入口全部<b>转发给原控件</b>，不另写一套流程：历史 = {@code btn_history}，
     * 新建 = {@code btn_new_conversation}，模型 = {@code btn_model_select}，
     * 这些控件在 v2 下被隐藏但 performClick 依然有效。</p>
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
        click(header, R.id.chat_v2_btn_more, v -> openInfoSheet(ctx));
        click(header, R.id.chat_v2_btn_model, v ->
                ModelSheet.show(activity, () -> updateSubtitle(activity, modelSubtitle(activity))));
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

    // ---------------------------------------------------------------- 信息面板

    /**
     * 「⋯」面板：模型 / token / 生成阶段 / KV / 余额 / 运行日志 / 智能体控制台 / 界面开关。
     * 值直接读被收起的那几个 TextView（原逻辑仍在刷新它们）。
     */
    private static void openInfoSheet(Context ctx) {
        try {
            BottomSheetDialog sheet = new BottomSheetDialog(ctx);
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(ctx, 18);
            box.setPadding(pad, pad, pad, pad);

            addRow(ctx, box, R.string.chat_v2_model, textOf(ctx, R.id.service_status_text));
            addRow(ctx, box, R.string.chat_v2_balance, textOf(ctx, R.id.tv_api_balance));
            addRow(ctx, box, R.string.chat_v2_tokens, textOf(ctx, R.id.tv_token_stats));
            addRow(ctx, box, R.string.chat_v2_phase, textOf(ctx, R.id.tv_gen_phase));
            addRow(ctx, box, R.string.chat_v2_kv, textOf(ctx, R.id.tv_kv_stats));
            addLinkRow(ctx, box, R.string.chat_v2_log,
                    v -> showPlainText(ctx, ctx.getString(R.string.chat_v2_log),
                            textOf(ctx, R.id.log_content)));
            addLinkRow(ctx, box, R.string.chat_v2_tools, v -> {
                // 复用原头部那颗汉堡键的点击逻辑（工具抽屉），不另写一套打开流程
                View tools = find(ctx, R.id.btn_back);
                if (tools != null) {
                    tools.performClick();
                } else {
                    Toast.makeText(ctx, R.string.chat_v2_no_data, Toast.LENGTH_SHORT).show();
                }
            });
            addLinkRow(ctx, box, R.string.chat_v2_clear, v -> {
                // v2 把旧空状态（里面有「清空」chip）整块收起来了，清空对话改从这里进
                View clear = find(ctx, R.id.chip_clear_chat2);
                if (clear != null) {
                    clear.performClick();
                } else {
                    Toast.makeText(ctx, R.string.chat_v2_no_data, Toast.LENGTH_SHORT).show();
                }
            });
            addLinkRow(ctx, box, R.string.chat_v2_console, v -> {
                try {
                    ctx.startActivity(new Intent(ctx,
                            com.oilquiz.app.ui.activity.AgentManagerActivity.class));
                } catch (Throwable t) {
                    Toast.makeText(ctx, R.string.chat_v2_no_data, Toast.LENGTH_SHORT).show();
                }
            });
            sheet.setContentView(box);
            sheet.show();
        } catch (Throwable t) {
            Log.w(TAG, "info sheet failed: " + t.getMessage());
        }
    }

    /** 纯文本弹层（运行日志用；日志面板收起后仍要能看到） */
    private static void showPlainText(Context ctx, String title, String content) {
        try {
            TextView tv = new TextView(ctx);
            tv.setText(TextUtils.isEmpty(content) ? ctx.getString(R.string.chat_v2_no_data) : content);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tv.setTextIsSelectable(true);
            int pad = dp(ctx, 16);
            tv.setPadding(pad, pad, pad, pad);
            android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
            scroll.addView(tv);
            new androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setView(scroll)
                    .setNegativeButton(R.string.close, null)
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "plain text dialog failed: " + t.getMessage());
        }
    }

    private static void addRow(Context ctx, LinearLayout box, int labelRes, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6));

        TextView label = new TextView(ctx);
        label.setText(labelRes);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setTextColor(ThemeColors.attr(ctx, R.attr.colorControlTextSecondary));
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f));

        TextView valueTv = new TextView(ctx);
        valueTv.setText(TextUtils.isEmpty(value) ? ctx.getString(R.string.chat_v2_no_data) : value);
        valueTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        valueTv.setTextColor(ThemeColors.attr(ctx, R.attr.colorControlText));
        valueTv.setSingleLine(true);
        valueTv.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(valueTv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f));
        box.addView(row);
    }

    /** 可点行（日志 / 控制台入口） */
    private static void addLinkRow(Context ctx, LinearLayout box, int labelRes,
                                   View.OnClickListener onClick) {
        TextView row = new TextView(ctx);
        row.setText(labelRes);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        row.setTextColor(ThemeColors.attr(ctx, R.attr.colorPrimary));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ctx, 40));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(onClick);
        box.addView(row);
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

    private static String textOf(Context ctx, int id) {
        TextView tv = find(ctx, id);
        return tv == null || tv.getText() == null ? "" : tv.getText().toString();
    }

    private static int dp(Context ctx, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }
}
