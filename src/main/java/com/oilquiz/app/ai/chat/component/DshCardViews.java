package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * DSH（电脑端）专用原生卡片。
 *
 * <p>背景：电脑端同步过来的事件此前几乎全部映射成 {@code alert} 通用提示卡——一张紫色
 * 左条 + 图标 + 两行字的“挂卡”，后台任务、命令执行、模型重试、上下文压缩、子代理、目标变更
 * 全是同一个长相，用户反馈“挂卡感觉不好”。这里给三类真正有语义的卡片各做一个原生 View：</p>
 *
 * <ul>
 *   <li>{@link DshCommandCard}（{@code dsh_command}）：一条终端命令 —— 等宽字体 + 状态灯 + 输出预览；</li>
 *   <li>{@link DshJobCard}（{@code dsh_job}）：后台任务 —— 状态点 + 标题 + 备注，一行一条；</li>
 *   <li>{@link DshNoticeCard}（{@code dsh_notice}）：生命周期提示（重试/压缩/子代理/目标）—— 紧凑单条，
 *       按级别着色，不再占领整张卡片的视觉重量。</li>
 * </ul>
 *
 * <p>三者都不带 emoji（沿用项目“状态去 emoji”约定），颜色走 {@link ComponentColors} 语义资源。
 * 数据来源是 {@code com.oilquiz.app.ai.remote.DshCards} 的事件映射，见那里的 {@code dsh_*} 分支。</p>
 */
final class DshCardViews {

    private DshCardViews() {
    }

    static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    /**
     * 提示卡全文弹窗（2026-11-14）：卡片上正文最多 3 行，点开看全 + 一键复制。
     *
     * <p>为什么用弹窗而不是"展开卡片"：这些提示卡是**历史里也要能看**的 durable 组件，
     * 展开状态没有地方落盘；弹窗无状态、点完即走，也不会让聊天页高度抖动。</p>
     */
    static void showNoticeDialog(Context context, String title, String text) {
        if (context == null) return;
        try {
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(context, 18);
            box.setPadding(pad, dp(context, 8), pad, 0);
            TextView body = new TextView(context);
            body.setText(text == null ? "" : text);
            body.setTextSize(13.5f);
            body.setTextColor(ComponentColors.textPrimary(context));
            body.setTextIsSelectable(true);   // 长按可直接选中复制
            box.addView(body);
            new android.app.AlertDialog.Builder(context)
                    .setTitle(TextUtils.isEmpty(title) ? "详情" : title)
                    .setView(box)
                    .setPositiveButton("复制", (d, w) -> copyText(context, text))
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            android.util.Log.w("DshCardViews", "提示卡详情弹窗失败: " + t);
        }
    }

    /** 复制到剪贴板 + 轻提示（Android 13+ 系统会自己弹复制提示，这里多一句也无害） */
    static void copyText(Context context, String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh", text == null ? "" : text));
            }
            android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            android.util.Log.w("DshCardViews", "复制失败: " + t);
        }
    }

    static GradientDrawable surface(Context context) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(ComponentColors.background(context));
        gd.setCornerRadius(dp(context, 10));
        gd.setStroke(dp(context, 1), ComponentColors.border(context));
        return gd;
    }

    /** 状态 → 语义色：running 用主题色，成功绿，失败红，其余次要灰。 */
    static int statusColor(Context context, String status) {
        if (status == null) return ComponentColors.textSecondary(context);
        switch (status) {
            case "success":
            case "completed":
            case "done":
                return ComponentColors.success(context);
            case "failed":
            case "error":
            case "cancelled":
                return ComponentColors.error(context);
            case "running":
            case "pending":
                return ComponentColors.accent(context);
            default:
                return ComponentColors.textSecondary(context);
        }
    }

    /** 命令/作业的状态中文标签（界面文案唯一出处）。 */
    static String statusLabel(String status) {
        if (status == null) return "";
        switch (status) {
            case "success":
            case "completed":
            case "done":
                return "已完成";
            case "failed":
            case "error":
                return "失败";
            case "cancelled":
                return "已取消";
            case "running":
                return "运行中";
            case "pending":
                return "等待中";
            default:
                return status;
        }
    }

    // ======================================================================
    // 1) 终端命令卡
    // ======================================================================

    /**
     * 一条命令执行（{@code dsh_command}）。
     *
     * <p>props：{@code {command, status, output?, cwd?}}。没有 command 时退回 command 为空串，
     * 由 {@code canRender} 拦掉，避免出现空白卡。</p>
     */
    static final class DshCommandCard implements ChatComponent {

        @Override
        public String getType() {
            return "dsh_command";
        }

        @Override
        public boolean canRender(ComponentData data) {
            return data != null && data.props != null
                    && !TextUtils.isEmpty(data.props.optString("command", ""));
        }

        @Override
        public View createView(Context context, ComponentData data) {
            JSONObject p = data.props != null ? data.props : new JSONObject();
            String command = p.optString("command", "");
            String status = p.optString("status", "");
            String output = p.optString("output", "");
            String cwd = p.optString("cwd", "");
            int accent = statusColor(context, status);

            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
            card.setBackground(surface(context));

            // 头部：终端标记 + 状态灯 + 状态文案
            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            TextView prompt = new TextView(context);
            prompt.setText("＞");
            prompt.setTextSize(13);
            prompt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            prompt.setTextColor(ComponentColors.textSecondary(context));
            header.addView(prompt);

            TextView label = new TextView(context);
            label.setText("终端");
            label.setTextSize(11);
            label.setTextColor(ComponentColors.textSecondary(context));
            label.setPadding(dp(context, 4), 0, dp(context, 8), 0);
            header.addView(label);

            String statusText = statusLabel(status);
            if (!statusText.isEmpty()) {
                TextView chip = new TextView(context);
                chip.setText(statusText);
                chip.setTextSize(11);
                chip.setTextColor(accent);
                chip.setPadding(dp(context, 6), dp(context, 1), dp(context, 6), dp(context, 1));
                GradientDrawable chipBg = new GradientDrawable();
                chipBg.setCornerRadius(dp(context, 8));
                chipBg.setStroke(dp(context, 1), accent);
                chip.setBackground(chipBg);
                header.addView(chip);
            }
            card.addView(header);

            // 命令本体：等宽、最多 4 行
            TextView cmd = new TextView(context);
            cmd.setText(command);
            cmd.setTextSize(12.5f);
            cmd.setTypeface(Typeface.MONOSPACE);
            cmd.setTextColor(ComponentColors.textPrimary(context));
            cmd.setMaxLines(4);
            cmd.setEllipsize(TextUtils.TruncateAt.END);
            cmd.setPadding(0, dp(context, 6), 0, 0);
            card.addView(cmd);

            if (!TextUtils.isEmpty(cwd)) {
                TextView cwdTv = new TextView(context);
                cwdTv.setText(cwd);
                cwdTv.setTextSize(10.5f);
                cwdTv.setMaxLines(1);
                cwdTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                cwdTv.setTextColor(ComponentColors.textSecondary(context));
                cwdTv.setPadding(0, dp(context, 2), 0, 0);
                card.addView(cwdTv);
            }

            if (!TextUtils.isEmpty(output)) {
                TextView out = new TextView(context);
                out.setText(output.trim());
                out.setTextSize(11.5f);
                out.setTypeface(Typeface.MONOSPACE);
                out.setTextColor("failed".equals(status) || "error".equals(status)
                        ? ComponentColors.error(context)
                        : ComponentColors.textSecondary(context));
                out.setMaxLines(3);
                out.setEllipsize(TextUtils.TruncateAt.END);
                out.setPadding(0, dp(context, 6), 0, 0);
                card.addView(out);
            }
            return card;
        }
    }

    // ======================================================================
    // 2) 后台任务卡
    // ======================================================================

    /**
     * 后台任务（{@code dsh_job}）。
     *
     * <p>props：{@code {label, status, detail?}}。原来这类事件画成“后台任务”标题 + 命令原文的
     * 提示卡，占一整块；现在压成一行：状态点 + 任务名 + 状态文案，多任务堆在同一张卡里。</p>
     */
    static final class DshJobCard implements ChatComponent {

        @Override
        public String getType() {
            return "dsh_job";
        }

        @Override
        public boolean canRender(ComponentData data) {
            return data != null && data.props != null
                    && !TextUtils.isEmpty(data.props.optString("label", ""));
        }

        @Override
        public View createView(Context context, ComponentData data) {
            JSONObject p = data.props != null ? data.props : new JSONObject();
            String label = p.optString("label", "");
            String status = p.optString("status", "running");
            String detail = p.optString("detail", "");
            int color = statusColor(context, status);

            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(context, 12), dp(context, 9), dp(context, 12), dp(context, 9));
            card.setBackground(surface(context));

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            View dot = new View(context);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(color);
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(context, 7), dp(context, 7));
            dotLp.setMargins(0, 0, dp(context, 8), 0);
            row.addView(dot, dotLp);

            TextView name = new TextView(context);
            name.setText(label);
            name.setTextSize(12.5f);
            name.setTextColor(ComponentColors.textPrimary(context));
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(name);

            String statusText = statusLabel(status);
            if (!statusText.isEmpty()) {
                TextView st = new TextView(context);
                st.setText(statusText);
                st.setTextSize(11);
                st.setTextColor(color);
                st.setPadding(dp(context, 6), 0, 0, 0);
                row.addView(st);
            }
            card.addView(row);

            if (!TextUtils.isEmpty(detail)) {
                TextView det = new TextView(context);
                det.setText(detail);
                det.setTextSize(11);
                det.setTextColor(ComponentColors.textSecondary(context));
                det.setMaxLines(2);
                det.setEllipsize(TextUtils.TruncateAt.END);
                det.setPadding(dp(context, 15), dp(context, 3), 0, 0);
                card.addView(det);
            }
            return card;
        }
    }

    // ======================================================================
    // 3) 生命周期提示卡
    // ======================================================================

    /**
     * 生命周期提示（{@code dsh_notice}）：模型重试、上下文压缩、子代理、目标变更、工作区改动。
     *
     * <p>props：{@code {title, text?, level: info|success|warning|error}}。刻意做成低权重的
     * 单行提示：左侧细色条 + 标题 + 说明，不再和用户内容抢注意力。</p>
     */
    static final class DshNoticeCard implements ChatComponent {

        @Override
        public String getType() {
            return "dsh_notice";
        }

        @Override
        public boolean canRender(ComponentData data) {
            return data != null && data.props != null
                    && (data.props.has("title") || data.props.has("text"));
        }

        @Override
        public View createView(Context context, ComponentData data) {
            JSONObject p = data.props != null ? data.props : new JSONObject();
            String title = p.optString("title", "");
            String text = p.optString("text", p.optString("content", ""));
            String level = p.optString("level", p.optString("type", "info"));

            int accent;
            switch (level) {
                case "success":
                    accent = ComponentColors.success(context);
                    break;
                case "warning":
                case "warn":
                    accent = ComponentColors.warning(context);
                    break;
                case "error":
                    accent = ComponentColors.error(context);
                    break;
                default:
                    accent = ComponentColors.accent(context);
                    break;
            }

            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setPadding(dp(context, 10), dp(context, 7), dp(context, 12), dp(context, 7));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(context, 8));
            bg.setColor(ComponentColors.background(context));
            card.setBackground(bg);

            View bar = new View(context);
            GradientDrawable barBg = new GradientDrawable();
            barBg.setCornerRadius(dp(context, 2));
            barBg.setColor(accent);
            bar.setBackground(barBg);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(context, 3),
                    LinearLayout.LayoutParams.MATCH_PARENT);
            barLp.setMargins(0, 0, dp(context, 9), 0);
            card.addView(bar, barLp);

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (!TextUtils.isEmpty(title)) {
                TextView tv = new TextView(context);
                tv.setText(title);
                tv.setTextSize(12);
                tv.setTextColor(ComponentColors.textPrimary(context));
                col.addView(tv);
            }
            if (!TextUtils.isEmpty(text)) {
                TextView tv = new TextView(context);
                tv.setText(text);
                tv.setTextSize(11.5f);
                tv.setTextColor(ComponentColors.textSecondary(context));
                tv.setMaxLines(3);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                if (!TextUtils.isEmpty(title)) tv.setPadding(0, dp(context, 2), 0, 0);
                col.addView(tv);
            }
            card.addView(col);
            /**
             * 2026-11-14：点击行为分两种。
             *
             * <ul>
             *   <li>卡片带 {@code jump} 属性 → <b>跳转</b>：{@code last}=滚到末尾那条（排队中的气泡）、
             *       {@code subagent}=滚到该子代理的轨迹卡（通过 {@link ComponentHosts} 找页面）；</li>
             *   <li>否则 → <b>展开全文</b>：正文最多 3 行，新绑定的几类事件信息量恰在正文里
             *       （插话内容、子代理模式、钩子输出、图片移出说明）。</li>
             * </ul>
             *
             * <p>长按始终是"查看全文 + 复制"，这样带跳转的卡也不会丢掉看全文的能力。</p>
             */
            final String jump = p.optString("jump", "");
            final String childId = p.optString("childId", "");
            final String fullTitle = title;
            final String fullText = text;
            boolean hasText = !TextUtils.isEmpty(text);
            if (!jump.isEmpty() || hasText) {
                card.setClickable(true);
                card.setOnClickListener(v -> {
                    ComponentHosts.Host host = ComponentHosts.get();
                    if ("last".equals(jump)) {
                        host.scrollToLast();
                        return;
                    }
                    if ("subagent".equals(jump)) {
                        host.openSubagent(childId);
                        return;
                    }
                    if (hasText) showNoticeDialog(context, fullTitle, fullText);
                });
                if ("last".equals(jump) || "subagent".equals(jump)) {
                    card.setOnLongClickListener(v -> {
                        if (hasText) showNoticeDialog(context, fullTitle, fullText);
                        return true;
                    });
                }
            }
            // 注意：这里**不要**自己再渲染 props.actions —— ComponentRegistry 对外层组件
            // 已经统一包了一层动作行（renderActions），这里再加一遍就出现两个"改权限"按钮
            // （2026-11-14 真机复现：一张卡两行按钮）。本类只负责卡面。
            return card;
        }
    }

    // ======================================================================
    // 4) 子代理轨迹卡（B15 durable）
    // ======================================================================

    /**
     * 子代理轨迹（{@code subagent_card}）：每个子代理一张卡，列出它最近跑过的工具与结果。
     *
     * <p>props：{@code {label, childId?, lastText?, steps:[{tool,args,status,result}]}}。
     * 这张卡由 {@code DshProjection} 从合成活动累积投影而来，**随消息落盘**——
     * 所以重进会话/重启后仍是同一张卡（durable），而不是"重连补发一帧"的临时状态。</p>
     */
    static final class DshSubagentCard implements ChatComponent {

        /** 最多显示几步（其余用"+N 步"提示，卡不被撑爆） */
        private static final int MAX_ROWS = 6;

        @Override
        public String getType() {
            return "subagent_card";
        }

        @Override
        public boolean canRender(ComponentData data) {
            return data != null && data.props != null
                    && (data.props.has("label") || data.props.has("steps"));
        }

        @Override
        public View createView(Context context, ComponentData data) {
            JSONObject p = data.props != null ? data.props : new JSONObject();
            String label = p.optString("label", "子代理");
            String lastText = p.optString("lastText", "");
            org.json.JSONArray steps = p.optJSONArray("steps");

            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(context, 12), dp(context, 9), dp(context, 12), dp(context, 9));
            card.setBackground(surface(context));

            LinearLayout head = new LinearLayout(context);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            View dot = new View(context);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(ComponentColors.accent(context));
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(context, 7), dp(context, 7));
            dotLp.setMargins(0, 0, dp(context, 8), 0);
            head.addView(dot, dotLp);

            TextView title = new TextView(context);
            title.setText("子代理 · " + label);
            title.setTextSize(12.5f);
            title.setTextColor(ComponentColors.textPrimary(context));
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(title);

            if (steps != null && steps.length() > 0) {
                TextView count = new TextView(context);
                count.setText(steps.length() + " 步");
                count.setTextSize(11);
                count.setTextColor(ComponentColors.textSecondary(context));
                head.addView(count);
            }
            card.addView(head);

            int shown = 0;
            if (steps != null) {
                for (int i = 0; i < steps.length() && shown < MAX_ROWS; i += 1, shown += 1) {
                    JSONObject st = steps.optJSONObject(i);
                    if (st == null) continue;
                    card.addView(stepRow(context, st));
                }
                if (steps.length() > shown) {
                    TextView more = new TextView(context);
                    more.setText("+" + (steps.length() - shown) + " 步");
                    more.setTextSize(11);
                    more.setTextColor(ComponentColors.textSecondary(context));
                    more.setPadding(dp(context, 15), dp(context, 2), 0, 0);
                    card.addView(more);
                }
            } else if (!TextUtils.isEmpty(lastText)) {
                TextView sub = new TextView(context);
                sub.setText(lastText);
                sub.setTextSize(11);
                sub.setTextColor(ComponentColors.textSecondary(context));
                sub.setMaxLines(2);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                sub.setPadding(dp(context, 15), dp(context, 3), 0, 0);
                card.addView(sub);
            } else {
                // 2026-11-14：刚创建、还没跑工具 —— 也给出"它存在且在跑"的可见信号，
                // 否则甲板卡槽里会空一块，用户以为子代理没起来
                TextView idle = new TextView(context);
                String mode = p.optString("mode", "");
                idle.setText(mode.isEmpty() ? "已启动，等待第一步…" : ("已启动 · 模式：" + mode));
                idle.setTextSize(11);
                idle.setTextColor(ComponentColors.textSecondary(context));
                idle.setPadding(dp(context, 15), dp(context, 3), 0, 0);
                card.addView(idle);
            }
            return card;
        }

        private View stepRow(Context context, JSONObject st) {
            String tool = st.optString("tool", "工具");
            String args = st.optString("args", "");
            String status = st.optString("status", "running");
            String result = st.optString("result", "");

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(context, 15), dp(context, 3), 0, 0);

            TextView line = new TextView(context);
            String text = "• " + tool + (TextUtils.isEmpty(args) ? "" : "（" + args + "）");
            line.setText(text);
            line.setTextSize(11.5f);
            line.setTextColor("failed".equals(status)
                    ? ComponentColors.error(context) : ComponentColors.textPrimary(context));
            line.setMaxLines(1);
            line.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(line);

            if (!TextUtils.isEmpty(result)) {
                TextView res = new TextView(context);
                res.setText(result);
                res.setTextSize(11);
                res.setTextColor(ComponentColors.textSecondary(context));
                res.setMaxLines(2);
                res.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(res);
            }
            return col;
        }
    }
}
