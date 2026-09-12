package com.oilquiz.app.ui.activity;

/*
 * AgentDebugActivity —— Agent 调试控制台（chatkit 视觉语言）。
 *
 * 实时显示 AgentDebugBridge 外部注入通道的连通状态与执行全过程：
 *   - 顶栏状态胶囊（待触发 / 执行中 / 完成 / 错误）
 *   - 通道开关 + Token + 连通状态
 *   - TokenStatsBar 实时 token 统计条（复用 chatkit 组件）
 *   - 消息流日志（类型着色：token/思考/步骤/工具/完成/错误）
 *
 * 联动：PC 端可同时用 adb logcat -s AgentDebugBridge 与
 *      /sdcard/Android/data/com.oilquiz.app/files/agent_bridge/result_*.txt 观测。
 */
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.AgentSession;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;
import com.oilquiz.app.ai.chat.ui.TokenStatsBar;
import com.oilquiz.app.infra.AgentDebugBridge;

public class AgentDebugActivity extends AppCompatActivity implements TokenStatsBar.StatsSource {

    // ---------- UI ----------
    private SwitchMaterial switchBridge;
    private TextView tvBridgeState;
    private TextView tvToken;
    private TextView tvConnState;
    private TextView tvStatusPill;
    private TextView tvTokenStats;
    private TextView tvLogCount;
    private LinearLayout llLogs;
    private ScrollView scrollLog;
    private EditText etPrompt;
    private TokenStatsBar tokenBar;

    // ---------- 智能体会话 ----------
    private AgentSession agentSession;

    // ---------- token 统计（TokenStatsBar.StatsSource） ----------
    private final Object statLock = new Object();
    private volatile boolean generating;
    private int onlineCompletionTokens;
    private long lastTokenTs;
    private int tokenBatchCount;
    private int logCount;

    // ---------- 事件流 ----------
    private final AgentDebugBridge.BridgeListener listener = new AgentDebugBridge.BridgeListener() {
        @Override
        public void onEvent(final String line) {
            runOnUiThread(() -> onBridgeLine(line));
        }

        @Override
        public void onState(final String key, final String value) {
            runOnUiThread(() -> {
                if ("connection".equals(key)) {
                    tvConnState.setText(value);
                    if (!generating) setPill("执行中", R.drawable.bg_pill_blue, "#93c5fd");
                } else if ("status".equals(key)) {
                    tvConnState.setText(value);
                    if (value.startsWith("✅")) setPill("完成", R.drawable.bg_pill_green, "#6ee7b7");
                    else if (value.startsWith("❌") || value.startsWith("⏱"))
                        setPill("结束", R.drawable.bg_pill_red, "#fca5a5");
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_debug);

        switchBridge = findViewById(R.id.switch_bridge);
        tvBridgeState = findViewById(R.id.tv_bridge_state);
        tvToken = findViewById(R.id.tv_token);
        tvConnState = findViewById(R.id.tv_conn_state);
        tvStatusPill = findViewById(R.id.tv_status_pill);
        tvTokenStats = findViewById(R.id.tv_token_stats);
        tvLogCount = findViewById(R.id.tv_log_count);
        llLogs = findViewById(R.id.ll_logs);
        scrollLog = findViewById(R.id.scroll_log);
        etPrompt = findViewById(R.id.et_prompt);

        tokenBar = new TokenStatsBar(this, tvTokenStats, this);

        refreshBridgeState();

        switchBridge.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AgentDebugBridge.setEnabled(AgentDebugActivity.this, isChecked);
            refreshBridgeState();
            Toast.makeText(this, isChecked ? "外部注入通道已开启" : "外部注入通道已关闭", Toast.LENGTH_SHORT).show();
        });

        MaterialButton btnCopy = findViewById(R.id.btn_copy_token);
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("AgentBridgeToken", tvToken.getText()));
                Toast.makeText(this, "Token 已复制", Toast.LENGTH_SHORT).show();
            }
        });

        MaterialButton btnClear = findViewById(R.id.btn_clear_log);
        btnClear.setOnClickListener(v -> {
            llLogs.removeAllViews();
            logCount = 0;
            tvLogCount.setText("0 条");
            onlineCompletionTokens = 0;
            tokenBatchCount = 0;
            generating = false;
            tokenBar.show(false);
        });

        // ---------- 本地智能体接入 ----------
        MaterialButton btnRun = findViewById(R.id.btn_run_agent);
        btnRun.setOnClickListener(v -> runLocalAgent());
        MaterialButton btnStop = findViewById(R.id.btn_stop_agent);
        btnStop.setOnClickListener(v -> stopLocalAgent());
        etPrompt.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                runLocalAgent();
                return true;
            }
            return false;
        });

        addLogRow("sys", "── Agent 调试控制台已打开 ──");
        setPill("待触发", R.drawable.bg_pill_gray, "#94a3b8");
    }

    // ---------- 本地智能体执行（AgentSession 标准接入） ----------

    private void runLocalAgent() {
        String prompt = etPrompt.getText() == null ? "" : etPrompt.getText().toString().trim();
        if (prompt.isEmpty()) {
            Toast.makeText(this, "先输入指令", Toast.LENGTH_SHORT).show();
            return;
        }
        if (agentSession != null && agentSession.isBusy()) {
            Toast.makeText(this, "智能体执行中，先停止再运行", Toast.LENGTH_SHORT).show();
            return;
        }
        if (agentSession == null) {
            agentSession = AgentSession.create(this);
        }
        generating = true;
        onlineCompletionTokens = 0;
        tokenBatchCount = 0;
        setPill("执行中", R.drawable.bg_pill_blue, "#93c5fd");
        tvConnState.setText("本地智能体执行中…");
        addLogRow("sys", "── 本地运行: " + prompt + " ──");

        agentSession.setCallback(new AgentCallback() {
            @Override public void onToken(String token) {
                runOnUiThread(() -> onAgentToken(token));
            }
            @Override public void onThinkingToken(String token) {
                runOnUiThread(() -> onAgentThinking(token));
            }
            @Override public void onThinkingEnd() {
                runOnUiThread(() -> addLogRow("dim", "── 思考结束 ──"));
            }
            @Override public void onToolCallStart(String id, String name, String args) {
                runOnUiThread(() -> addLogRow("tool", "▶ 工具调用: " + name + " " + args));
            }
            @Override public void onToolCallComplete(String id, String name, OnlineToolResult result) {
                runOnUiThread(() -> addLogRow("toolok", "✔ 工具完成: " + name + " -> " + result));
            }
            @Override public void onStepUpdate(String step, String detail) {
                runOnUiThread(() -> addLogRow("step", "[STEP] " + step + " " + detail));
            }
            @Override public void onComplete(String fullText) {
                runOnUiThread(() -> {
                    generating = false;
                    setPill("完成", R.drawable.bg_pill_green, "#6ee7b7");
                    tokenBar.show(false);
                    tvConnState.setText("✅ 本地执行完成");
                    addLogRow("done", "── 执行完成 ──");
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    generating = false;
                    setPill("错误", R.drawable.bg_pill_red, "#fca5a5");
                    tokenBar.show(false);
                    tvConnState.setText("❌ 错误: " + error);
                    addLogRow("err", "── 执行出错: " + error + " ──");
                });
            }
        });
        agentSession.start(prompt, 4096, true);
    }

    private void stopLocalAgent() {
        if (agentSession != null && agentSession.isBusy()) {
            agentSession.stop();
            generating = false;
            setPill("已停止", R.drawable.bg_pill_gray, "#94a3b8");
            tvConnState.setText("已手动停止");
            addLogRow("dim", "── 手动停止 ──");
        } else {
            Toast.makeText(this, "当前无执行中的任务", Toast.LENGTH_SHORT).show();
        }
    }

    // 本地回调 token/思考 token（与外部注入共用统计与消息流）
    private void onAgentToken(String token) {
        synchronized (statLock) {
            onlineCompletionTokens++;
            tokenBatchCount++;
            long now = System.currentTimeMillis();
            float tps = 0;
            if (lastTokenTs > 0) {
                long dt = now - lastTokenTs;
                if (dt > 0) tps = 1000f / dt;
            }
            lastTokenTs = now;
            tokenBar.updateFromStreaming(onlineCompletionTokens, tps);
        }
        addLogRow("token", token);
    }

    private void onAgentThinking(String token) {
        addLogRow("think", token);
    }

    private void refreshBridgeState() {
        boolean on = AgentDebugBridge.isEnabled(this);
        switchBridge.setChecked(on);
        tvBridgeState.setText(on ? "已开启 · 外部广播可注入" : "已关闭 · 点击开启");
        tvToken.setText(AgentDebugBridge.getToken(this));
    }

    // ---------- 事件解析 ----------

    private void onBridgeLine(String line) {
        if (line == null) return;
        if (line.startsWith("TOKEN: ")) {
            generating = true;
            synchronized (statLock) {
                onlineCompletionTokens++;
                tokenBatchCount++;
                long now = System.currentTimeMillis();
                float tps = 0;
                if (lastTokenTs > 0) {
                    long dt = now - lastTokenTs;
                    if (dt > 0) tps = 1000f / dt;
                }
                lastTokenTs = now;
                tokenBar.updateFromStreaming(onlineCompletionTokens, tps);
            }
            addLogRow("token", line.substring(7).trim());
        } else if (line.startsWith("THINK: ")) {
            generating = true;
            addLogRow("think", line.substring(7).trim());
        } else if (line.startsWith("[STEP] ")) {
            generating = true;
            addLogRow("step", line.substring(7));
        } else if (line.startsWith("▶ ")) {
            generating = true;
            addLogRow("tool", line.substring(2));
        } else if (line.startsWith("✔ ")) {
            addLogRow("toolok", line.substring(2));
        } else if (line.contains("思考结束")) {
            addLogRow("dim", line);
        } else if (line.contains("执行完成")) {
            generating = false;
            setPill("完成", R.drawable.bg_pill_green, "#6ee7b7");
            tokenBar.show(false);
            addLogRow("done", line);
        } else if (line.contains("执行出错") || line.contains("初始化失败")) {
            generating = false;
            setPill("错误", R.drawable.bg_pill_red, "#fca5a5");
            tokenBar.show(false);
            addLogRow("err", line);
        } else {
            addLogRow("sys", line);
        }
    }

    // ---------- 消息流 ----------

    private void addLogRow(String kind, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.TOP);
        row.setPadding(0, 3, 0, 3);

        // 类型色块
        View bar = new View(this);
        int color = kindColor(kind);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(3, 0);
        barLp.height = dp(15);
        barLp.topMargin = dp(5);
        bar.setLayoutParams(barLp);
        bar.setBackgroundColor(color);
        row.addView(bar);

        // 时间 + 文本
        String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
        TextView tx = new TextView(this);
        tx.setTextSize(11f);
        tx.setTextColor(color);
        tx.setIncludeFontPadding(false);
        tx.setPadding(dp(8), 0, 0, 0);
        tx.setText(ts + "  " + text);
        row.addView(tx, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        llLogs.addView(row);
        logCount++;
        tvLogCount.setText(logCount + " 条");
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        if (logCount > 400) {
            llLogs.removeViews(0, logCount - 300);
            logCount = 300;
        }
    }

    private int kindColor(String kind) {
        switch (kind) {
            case "token": return Color.parseColor("#7dd3fc");
            case "think": return Color.parseColor("#c4b5fd");
            case "step": return Color.parseColor("#93c5fd");
            case "tool":
            case "toolok": return Color.parseColor("#fcd34d");
            case "done": return Color.parseColor("#6ee7b7");
            case "err": return Color.parseColor("#fca5a5");
            case "dim": return Color.parseColor("#64748b");
            default: return Color.parseColor("#94a3b8");
        }
    }

    private void setPill(String text, int bgRes, String colorHex) {
        tvStatusPill.setText("● " + text);
        tvStatusPill.setBackgroundResource(bgRes);
        tvStatusPill.setTextColor(Color.parseColor(colorHex));
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ---------- TokenStatsBar.StatsSource ----------

    @Override public boolean isUsingOnlineModel() { return true; }
    @Override public boolean isGenerating() { return generating; }
    @Override public int getOnlineCompletionTokens() { return onlineCompletionTokens; }
    @Override public int getOnlinePromptTokens() { return 0; }
    @Override public float getPhaseSpeed() { return 0; }
    @Override public float getNativeInferenceSpeed() { return 0; }
    @Override public int getNativeTokenCount() { return 0; }
    @Override public String getGenPhase() { return null; }
    @Override public long getStreamingTokenCount() { return onlineCompletionTokens; }
    @Override public int getLastCacheHitTokens() { return 0; }
    @Override public int getLastPromptTokens() { return 0; }
    @Override public int[] getContextWindowInfo() { return null; }

    @Override
    protected void onResume() {
        super.onResume();
        AgentDebugBridge.setListener(listener);
        refreshBridgeState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        AgentDebugBridge.setListener(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (agentSession != null) {
            try {
                agentSession.shutdown();
            } catch (Throwable ignored) {
            }
            agentSession = null;
        }
    }
}
