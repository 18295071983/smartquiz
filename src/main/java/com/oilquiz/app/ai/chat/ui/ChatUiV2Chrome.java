package com.oilquiz.app.ai.chat.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
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
 * 开关（{@link ChatUiFlags} 默认关）打开时，把技术信息行（服务状态条、远程 DSH 连接条、
 * 统计条里的 token 段、日志面板）收起来，插一条只讲"在和哪个模型说话"的顶栏，
 * 技术细节改由右上「⋯」的 BottomSheet 承载。这样新旧两套能并行跑，出问题关开关即回原样。
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

    private ChatUiV2Chrome() {
    }

    /** 开关关闭时什么都不做；开启时改造外壳 */
    public static void apply(Activity activity) {
        if (activity == null) return;
        try {
            if (!ChatUiFlags.isUiV2Enabled(activity)) return;
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
        compressStatsBar(activity);

        ViewGroup root = activity.findViewById(R.id.main_content);
        if (root == null || root.findViewWithTag(TAG_HEADER) != null) return;

        LinearLayout header = buildHeader(activity, root);
        header.setTag(TAG_HEADER);
        root.addView(header, 0);
    }

    // ---------------------------------------------------------------- 顶栏

    private static LinearLayout buildHeader(Activity activity, ViewGroup parent) {
        Context ctx = activity;
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(ctx, 14);
        header.setPadding(pad, dp(ctx, 8), dp(ctx, 6), dp(ctx, 6));

        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(ctx);
        title.setText(R.string.chat_v2_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTextColor(ThemeColors.attr(ctx, R.attr.colorControlText));
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        titles.addView(title);

        TextView subtitle = new TextView(ctx);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        subtitle.setTextColor(ThemeColors.attr(ctx, R.attr.colorControlTextSecondary));
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setText(modelSubtitle(ctx));
        titles.addView(subtitle);

        header.addView(titles);
        header.addView(iconButton(ctx, R.drawable.ic_add, R.string.chat_v2_new_desc, v -> {
            // 复用历史抽屉里的"新建对话"逻辑，不另写一套会话切换
            View btn = activity.findViewById(R.id.btn_new_conversation);
            if (btn instanceof View && btn.isClickable()) {
                btn.performClick();
            } else {
                Toast.makeText(ctx, R.string.chat_v2_no_data, Toast.LENGTH_SHORT).show();
            }
        }));
        header.addView(iconButton(ctx, R.drawable.ic_more_vert, R.string.chat_v2_more_desc,
                v -> openInfoSheet(ctx)));
        return header;
    }

    /** 副标题：优先服务状态条上的模型文案（原逻辑一直在刷新它） */
    private static String modelSubtitle(Context ctx) {
        TextView status = find(ctx, R.id.service_status_text);
        if (status != null && status.getText() != null && status.getText().length() > 0) {
            return status.getText().toString();
        }
        return ctx.getString(R.string.chat_v2_no_data);
    }

    private static ImageView iconButton(Context ctx, int iconRes, int descRes,
                                        View.OnClickListener onClick) {
        ImageView iv = new ImageView(ctx);
        int size = dp(ctx, 40);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginStart(dp(ctx, 2));
        iv.setLayoutParams(lp);
        iv.setPadding(dp(ctx, 9), dp(ctx, 9), dp(ctx, 9), dp(ctx, 9));
        iv.setImageResource(iconRes);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(
                ThemeColors.attr(ctx, R.attr.colorControlTextSecondary)));
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setContentDescription(ctx.getString(descRes));
        iv.setClickable(true);
        iv.setFocusable(true);
        iv.setOnClickListener(onClick);
        return iv;
    }

    // ---------------------------------------------------------------- 输入区密度

    /** 输入区上方的模式 chip 压到一行 28dp（v2 只调密度，不改交互） */
    private static void compressStatsBar(Activity activity) {
        int[] chips = {R.id.chip_local_agent, R.id.chip_deep_think, R.id.chip_web_search};
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
        }
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
            addLinkRow(ctx, box, R.string.chat_v2_console, v -> {
                try {
                    ctx.startActivity(new Intent(ctx,
                            com.oilquiz.app.ui.activity.AgentManagerActivity.class));
                } catch (Throwable t) {
                    Toast.makeText(ctx, R.string.chat_v2_no_data, Toast.LENGTH_SHORT).show();
                }
            });
            addSwitchRow(ctx, box);

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

    private static void addSwitchRow(Context ctx, LinearLayout box) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 10), 0, dp(ctx, 4));

        TextView label = new TextView(ctx);
        label.setText(R.string.chat_v2_ui_switch);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setTextColor(ThemeColors.attr(ctx, R.attr.colorControlText));
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(ctx);
        toggle.setChecked(ChatUiFlags.isUiV2Enabled(ctx));
        row.addView(toggle);

        toggle.setOnCheckedChangeListener((b, checked) -> {
            ChatUiFlags.setUiV2Enabled(ctx, checked);
            Toast.makeText(ctx, R.string.chat_v2_restart_hint, Toast.LENGTH_SHORT).show();
        });
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
