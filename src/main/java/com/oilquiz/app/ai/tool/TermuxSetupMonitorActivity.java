package com.oilquiz.app.ai.tool;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;

/**
 * 一键准备 · 专用执行监控页。
 *
 * <p>每 2 秒轮询 Termux 公共日志（setup_log.txt），解析 8 个步骤的状态
 * （✅ 完成 / ❌ 失败 / ⏳ 进行中 / ⬜ 未开始），大日志区实时滚动显示原始输出；
 * 检测到 🎉（完成）或 ❌（某步失败）自动停。停止监控不终止 Termux 里的执行进程。
 */
public class TermuxSetupMonitorActivity extends AppCompatActivity {

    private TextView stepsView;
    private TextView statusView;
    private TextView logView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean monitoring = true;
    private long startTs;

    /** 步骤名（与 runner 的 STEPS 顺序一致） */
    private static final String[] STEP_NAMES = {"0", "1", "2", "3", "3_zh", "4", "5", "6"};

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            tick();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_termux_setup_monitor);
        stepsView = findViewById(R.id.monitor_steps);
        statusView = findViewById(R.id.monitor_status);
        logView = findViewById(R.id.monitor_log);
        logView.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        MaterialButton stopBtn = findViewById(R.id.btn_monitor_stop);
        stopBtn.setOnClickListener(v -> {
            monitoring = false;
            handler.removeCallbacks(pollRunnable);
            statusView.setText("已停止监控 —— Termux 里的准备进程仍在跑，切回本页点「查看环境日志」可看结果。");
        });
        startTs = System.currentTimeMillis();
        handler.post(pollRunnable);
    }

    private void tick() {
        if (!monitoring) {
            return;
        }
        String tail = TermuxEnvInstaller.readPublicSetupLog(this);
        if (!tail.isEmpty()) {
            String clean = tail.length() > 8000 ? tail.substring(tail.length() - 8000) : tail;
            logView.setText(clean);
            logView.post(() -> {
                if (logView.getLayout() != null) {
                    logView.scrollTo(0, Math.max(0, logView.getLayout().getHeight() - logView.getHeight()));
                }
            });
            updateSteps(tail);
        } else {
            long sec = (System.currentTimeMillis() - startTs) / 1000;
            statusView.setText("⏳ 等待 Termux 开始输出…（已等 " + sec + " 秒；若很久没动静，检查 Termux 是否在跑）");
        }
        if (tail.contains("🎉") || tail.contains("❌")) {
            monitoring = false;
            return;
        }
        handler.postDelayed(pollRunnable, 2000);
    }

    /** 按日志里的 "===== stepX =====" 段解析 8 步状态 */
    private void updateSteps(String tail) {
        StringBuilder sb = new StringBuilder("步骤：");
        String current = "等待中";
        boolean anyRun = false;
        for (String name : STEP_NAMES) {
            String st = stepState(tail, name);
            String icon = "⬜";
            if ("done".equals(st)) icon = "✅";
            else if ("fail".equals(st)) icon = "❌";
            else if ("run".equals(st)) { icon = "⏳"; anyRun = true; current = "step" + name + " 执行中"; }
            sb.append(" ").append(name).append(icon);
        }
        stepsView.setText(sb.toString());
        long sec = (System.currentTimeMillis() - startTs) / 1000;
        if (tail.contains("🎉")) {
            statusView.setText("✅ 全部完成！用时 " + sec + " 秒 —— Termux 里输入 ~/ubuntu 进入容器。");
        } else if (tail.contains("❌")) {
            String failed = failedStep(tail);
            statusView.setText("❌ 第 " + failed + " 步失败（用时 " + sec + " 秒）。\n看上方 ❌ 行找原因；重跑会自动跳过已完成的步骤。");
        } else {
            statusView.setText("⏳ " + current + " · 已执行 " + sec + " 秒");
        }
    }

    /** 某步骤状态：done/fail/run/wait（按该 step 段内 ✅/❌ 判断） */
    private String stepState(String tail, String name) {
        String mark = "===== step" + name + " =====";
        int idx = tail.indexOf(mark);
        if (idx < 0) {
            return "wait";
        }
        String rest = tail.substring(idx);
        int next = rest.indexOf("===== step", 1);
        String seg = next > 0 ? rest.substring(0, next) : rest;
        if (seg.contains("❌")) {
            return "fail";
        }
        if (seg.contains("✅")) {
            return "done";
        }
        return "run";
    }

    /** 失败步骤名（日志里 "❌ stepX 执行失败" 或段内 ❌） */
    private String failedStep(String tail) {
        for (String name : STEP_NAMES) {
            if (tail.contains("❌ step" + name + " 执行失败")) {
                return "step" + name;
            }
        }
        // 兜底：最后一段含 ❌ 的 step
        String last = "";
        for (String name : STEP_NAMES) {
            if ("fail".equals(stepState(tail, name))) {
                last = "step" + name;
            }
        }
        return last.isEmpty() ? "未知" : last;
    }

    @Override
    protected void onDestroy() {
        monitoring = false;
        handler.removeCallbacks(pollRunnable);
        super.onDestroy();
    }
}
