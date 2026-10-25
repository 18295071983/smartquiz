package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
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
        return data != null && data.props != null
                && !data.props.optString("job_id", "").trim().isEmpty();
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        return new JobCardRoot(context,
                p.optString("job_id", "").trim(),
                p.optString("title", ""),
                p.optString("cmd", ""));
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
