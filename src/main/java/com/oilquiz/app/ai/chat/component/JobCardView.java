package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.oilquiz.app.util.PublicStorageWriter;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 长任务"活动卡"（type=job）：卡片原生 View 在 attach 期间自己轮询公共作业目录
 * {@code Download/OilQuiz/jobs/<id>.{log,rc,done}}，刷新状态与日志尾部——
 * 不需要模型再调工具、也不改动已落定的消息内容（复用 {@link AudioCardView} 的 attach 生命周期自刷模式）。
 *
 * <p>props：{@code {job_id, title?, cmd?}}。判定完成＝出现 &lt;id&gt;.done（内容由 runner 落退出码，
 * 见 {@code android_termux.submit} / {@code linux_shell(termux_submit)}）。
 * 划出屏幕/列表回收 → 停轮询；回到屏幕 → 续轮询并补一次当前状态。作业本身在 Termux 里继续跑，
 * 卡只是它的只读镜像。实时日志不进 props、每次现读，避免把大日志写进会话历史。
 */
public class JobCardView implements ChatComponent {

    /** 所有 job 卡共用一条 IO 线程读公共目录小文件；卡本身不各自起线程（避免随会话堆积泄漏）。 */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    @Override
    public String getType() {
        return "job";
    }

    @Override
    public boolean canRender(ComponentData data) {
        if (data == null || data.props == null) return false;
        // 2026-11-14：新增"静态状态"用法（DSH 的 job 事件）——没有 job_id、只有 status 也能画；
        // 有 job_id 才是原来那种主动轮询的作业卡。
        return !data.props.optString("job_id", "").trim().isEmpty()
                || !data.props.optString("status", "").trim().isEmpty();
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String jid = p.optString("job_id", "").trim();
        String status = p.optString("status", "").trim();
        if (jid.isEmpty() && !status.isEmpty()) {
            return staticStatusView(context, p.optString("title", "后台任务"), status);
        }
        return new JobCardRoot(context,
                jid,
                p.optString("title", ""),
                p.optString("cmd", ""));
    }

    /**
     * 静态状态卡（2026-11-14）：给 DSH 的 {@code job/registered|settled} 用。
     *
     * <p>DSH 的后台任务在宿主侧跑，手机拿不到日志、也不该去轮询本地作业目录；
     * 只需要"运行中 / 完成 / 失败"一眼可见。这样原来的 {@code dsh_job} 原生卡就可以删掉，
     * 任务状态统一走内置的 {@code job} 组件。</p>
     */
    private static View staticStatusView(Context context, String title, String status) {
        boolean failed = "failed".equals(status) || "error".equals(status) || "cancelled".equals(status);
        boolean running = "running".equals(status) || "queued".equals(status) || "pending".equals(status);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int pad = px(context, 12);
        card.setPadding(pad, px(context, 9), pad, px(context, 9));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(context, 10));
        bg.setColor(ComponentColors.background(context));
        bg.setStroke(px(context, 1), ComponentColors.border(context));
        card.setBackground(bg);

        View dot = new View(context);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(failed ? ComponentColors.error(context)
                : running ? ComponentColors.warning(context) : ComponentColors.success(context));
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(px(context, 8), px(context, 8));
        dotLp.setMargins(0, 0, px(context, 8), 0);
        card.addView(dot, dotLp);

        TextView tv = new TextView(context);
        tv.setText(title == null || title.isEmpty() ? "后台任务" : title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTextColor(ComponentColors.textPrimary(context));
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(tv);

        TextView state = new TextView(context);
        state.setText(running ? "运行中" : failed ? "失败" : "完成");
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        state.setTextColor(failed ? ComponentColors.error(context)
                : running ? ComponentColors.warning(context) : ComponentColors.textSecondary(context));
        state.setPadding(px(context, 8), 0, 0, 0);
        card.addView(state);
        return card;
    }
    /** dp→px（外层静态方法用；内层的 dp 在 JobCardRoot 里） */
    private static int px(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /** 卡片根视图：持有 attach 作用域的轮询器。 */
    static final class JobCardRoot extends LinearLayout {
        private static final long POLL_MS = 2000L;
        private static final int LOG_TAIL_BYTES = 4000;

        private final String jid;
        private final String baseTitle;
        private final TextView statusView;
        private final TextView logView;
        private final ProgressBar spinner;
        private final Handler main = new Handler(Looper.getMainLooper());
        private volatile boolean polling;
        private boolean finished;

        JobCardRoot(Context context, String jid, String title, String cmd) {
            super(context);
            this.jid = jid;
            this.baseTitle = (title != null && !title.isEmpty()) ? title : ("作业 " + jid);
            setOrientation(VERTICAL);
            int pad = dp(context, 12);
            setPadding(pad, pad, pad, pad);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(context, 10));
            bg.setColor(Color.parseColor("#F5F5F5"));
            bg.setStroke(dp(context, 1), Color.parseColor("#E0E0E0"));
            setBackground(bg);

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            spinner = new ProgressBar(context);
            spinner.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 18), dp(context, 18)));
            header.addView(spinner);

            statusView = new TextView(context);
            statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            statusView.setTypeface(null, Typeface.BOLD);
            statusView.setTextColor(Color.parseColor("#333333"));
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                    0, LayoutParams.WRAP_CONTENT, 1f);
            stLp.leftMargin = dp(context, 8);
            statusView.setLayoutParams(stLp);
            statusView.setText(baseTitle + " · 启动中…");
            header.addView(statusView);
            addView(header);

            if (cmd != null && !cmd.isEmpty()) {
                TextView cmdView = new TextView(context);
                cmdView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                cmdView.setTextColor(Color.parseColor("#888888"));
                cmdView.setSingleLine(true);
                cmdView.setText("$ " + cmd);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                clp.topMargin = dp(context, 4);
                cmdView.setLayoutParams(clp);
                addView(cmdView);
            }

            logView = new TextView(context);
            logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            logView.setTypeface(Typeface.MONOSPACE);
            logView.setTextColor(Color.parseColor("#444444"));
            ScrollView scroll = new ScrollView(context);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(context, 140));
            slp.topMargin = dp(context, 6);
            scroll.setLayoutParams(slp);
            scroll.addView(logView);
            addView(scroll);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!finished) {
                startPolling();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopPolling();
        }

        private void startPolling() {
            if (polling || finished) {
                return;
            }
            polling = true;
            main.post(tick);
        }

        private void stopPolling() {
            polling = false;
            main.removeCallbacks(tick);
        }

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!polling || finished) {
                    return;
                }
                IO.submit(() -> {
                    Context c = getContext();
                    final String log = PublicStorageWriter.readTail(c, "jobs", jid + ".log", LOG_TAIL_BYTES);
                    // 完成信号用 .rc（runner 用 printf 写入，非空）：不能用 PublicStorageWriter.exists(.done)，
                    // 因为 .done 是 touch 出来的 0 字节、exists 判 size>0 会恒 false（真机 bug）。
                    final String rc = PublicStorageWriter.readTail(c, "jobs", jid + ".rc", 32);
                    final boolean done = rc != null && !rc.trim().isEmpty();
                    final int exit = done ? parseExit(rc) : -1;
                    main.post(() -> apply(done, exit, log));
                    return null;
                });
                if (polling && !finished) {
                    main.postDelayed(this, POLL_MS);
                }
            }
        };

        private void apply(boolean done, int exit, String log) {
            if (!isAttachedToWindow()) {
                return;
            }
            if (log != null && !log.isEmpty()) {
                logView.setText(log);
                View parent = (View) logView.getParent();
                if (parent instanceof ScrollView) {
                    ((ScrollView) parent).fullScroll(ScrollView.FOCUS_DOWN);
                }
            }
            if (done) {
                finished = true;
                stopPolling();
                spinner.setVisibility(View.GONE);
                statusView.setTextColor(exit == 0 ? Color.parseColor("#2E7D32")
                        : Color.parseColor("#C62828"));
                statusView.setText(baseTitle + (exit == 0 ? " · ✅ 完成" : " · ❌ 结束，退出码 " + exit));
            } else {
                statusView.setText(baseTitle + " · 运行中…");
            }
        }

        static int parseExit(String rc) {
            if (rc == null) {
                return -1;
            }
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < rc.length(); i++) {
                char ch = rc.charAt(i);
                if (ch == '-' || (ch >= '0' && ch <= '9')) {
                    digits.append(ch);
                } else if (digits.length() > 0) {
                    break;
                }
            }
            try {
                return digits.length() == 0 ? -1 : Integer.parseInt(digits.toString());
            } catch (Exception e) {
                return -1;
            }
        }

        static int dp(Context c, float v) {
            return Math.round(v * c.getResources().getDisplayMetrics().density);
        }
    }
}
