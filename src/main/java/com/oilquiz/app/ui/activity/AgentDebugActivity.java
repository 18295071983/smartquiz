package com.oilquiz.app.ui.activity;

/*
 * AgentDebugActivity —— Agent 调试控制台（chatkit 对话流 × 双视图）。
 *
 * 复用 chatkit 全局组件：
 *   - ChatAdapter + RecyclerView × 2：对话流（干净）/ 原始指令流（完整事件）
 *   - TokenStatsBar 实时 token 统计、InferenceStateManager 推理状态监听
 *   - ToolResultInterpreter 工具结果解读
 *   - AgentSession 标准接入本地运行智能体
 * 支持两路执行：本地输入驱动 / 外部 adb 注入（AgentDebugBridge 自动跳转）。
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.AgentSession;
import com.oilquiz.app.ai.agent.ToolResultInterpreter;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;
import com.oilquiz.app.ai.chat.ChatAdapter;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.InferenceStateManager;
import com.oilquiz.app.ai.chat.ui.TokenStatsBar;
import com.oilquiz.app.infra.AgentDebugBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AgentDebugActivity extends AppCompatActivity implements TokenStatsBar.StatsSource {

    // ---------- UI ----------
    private SwitchMaterial switchBridge;
    private TextView tvBridgeState;
    private TextView tvToken;
    private TextView tvConnState;
    private TextView tvStatusPill;
    private TextView tvTokenStats;
    private EditText etPrompt;
    private LinearLayout panelRaw;
    private boolean rawExpanded = true;
    private TokenStatsBar tokenBar;

    // ---------- 双流对话（chatkit） ----------
    private static class StreamState {
        final List<ChatMessage> msgs = new ArrayList<>();
        ChatAdapter adapter;
        RecyclerView rv;
        ChatMessage ai;              // 流式更新的助手消息
        final StringBuilder think = new StringBuilder();
    }
    private final StreamState dialog = new StreamState();  // 对话流
    private final StreamState raw = new StreamState();     // 原始指令流

    // ---------- 智能体会话 ----------
    private AgentSession agentSession;

    // ---------- token 统计（TokenStatsBar.StatsSource） ----------
    private final Object statLock = new Object();
    private volatile boolean generating;
    private int onlineCompletionTokens;
    private long lastTokenTs;

    // ---------- 事件流（外部注入） ----------
    private final AgentDebugBridge.BridgeListener listener = new AgentDebugBridge.BridgeListener() {
        @Override
        public void onEvent(final String line) {
            runOnUiThread(() -> applyEvent(dialog, line));   // 执行过程 → 对话流
        }

        @Override
        public void onRawEvent(final String line) {
            runOnUiThread(() -> {
                // 外部数据到达 → 自动展开原始指令流面板
                if (!rawExpanded) {
                    rawExpanded = true;
                    panelRaw.setVisibility(View.VISIBLE);
                    MaterialButton btn = findViewById(R.id.btn_toggle_raw);
                    btn.setText("原始指令流 ▴");
                }
                if (line == null) return;
                if (line.startsWith("[ANSWER] ")) {
                    // 模型最终回答：用 AI 消息渲染（走 MarkdownRenderer，加粗/列表/代码块生效）
                    addAi(raw, line.substring(9));
                } else if (line.startsWith("[PROMPT] ")) {
                    // 外部传入的提示词本体 → 用户气泡
                    addUser(raw, line.substring(9));
                } else {
                    // 注入元数据/校验/回执 → 系统消息（纯文本）
                    addSystem(raw, line);
                }
            });
        }

        @Override
        public void onState(final String key, final String value) {
            runOnUiThread(() -> {
                tvConnState.setText(value);
                if ("status".equals(key)) {
                    if (value.startsWith("✅")) {
                        setPill("完成", R.drawable.bg_pill_green, "#6ee7b7");
                        dialog.ai = null;
                        raw.ai = null;
                    } else if (value.startsWith("❌") || value.startsWith("⏱")) {
                        setPill("结束", R.drawable.bg_pill_red, "#fca5a5");
                        dialog.ai = null;
                        raw.ai = null;
                    }
                } else if ("connection".equals(key)) {
                    if (!generating) setPill("执行中", R.drawable.bg_pill_blue, "#93c5fd");
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
        etPrompt = findViewById(R.id.et_prompt);
        panelRaw = findViewById(R.id.panel_raw);

        tokenBar = new TokenStatsBar(this, tvTokenStats, this);

        // ---------- 双流 ChatAdapter ----------
        dialog.rv = findViewById(R.id.rv_chat);
        dialog.adapter = new ChatAdapter(dialog.msgs, action -> { });
        dialog.rv.setLayoutManager(new LinearLayoutManager(this));
        dialog.rv.setAdapter(dialog.adapter);
        dialog.rv.setItemAnimator(null);

        raw.rv = findViewById(R.id.rv_raw);
        raw.adapter = new ChatAdapter(raw.msgs, action -> { });
        raw.rv.setLayoutManager(new LinearLayoutManager(this));
        raw.rv.setAdapter(raw.adapter);
        raw.rv.setItemAnimator(null);

        // ---------- 全局逻辑组件：推理状态/进度监听（InferenceStateManager） ----------
        dialog.adapter.setInferenceProgressListener(new ChatAdapter.InferenceProgressUpdateListener() {
            @Override
            public void onInferenceStateChanged(String messageId,
                                                InferenceStateManager.InferenceState oldState,
                                                InferenceStateManager.InferenceState newState,
                                                InferenceStateManager.StateDetails details) {
                runOnUiThread(() -> {
                    if (newState == InferenceStateManager.InferenceState.GENERATING) {
                        generating = true;
                        setPill("执行中", R.drawable.bg_pill_blue, "#93c5fd");
                    } else if (newState == InferenceStateManager.InferenceState.COMPLETED) {
                        generating = false;
                        setPill("完成", R.drawable.bg_pill_green, "#6ee7b7");
                        tokenBar.show(false);
                        dialog.ai = null;
                        raw.ai = null;
                    } else if (newState == InferenceStateManager.InferenceState.FAILED
                            || newState == InferenceStateManager.InferenceState.TIMEOUT) {
                        generating = false;
                        setPill("错误", R.drawable.bg_pill_red, "#fca5a5");
                        tokenBar.show(false);
                        dialog.ai = null;
                        raw.ai = null;
                    } else if (newState == InferenceStateManager.InferenceState.CANCELLED) {
                        generating = false;
                        setPill("已停止", R.drawable.bg_pill_gray, "#94a3b8");
                        tokenBar.show(false);
                        dialog.ai = null;
                        raw.ai = null;
                    }
                });
            }

            @Override
            public void onInferenceProgressUpdated(String messageId,
                                                   InferenceStateManager.StateDetails details) {
                runOnUiThread(() -> {
                    if (details != null && details.currentPhase != null) {
                        tvConnState.setText(details.currentPhase
                                + (details.progressPercent > 0 ? " · " + details.progressPercent + "%" : ""));
                    }
                });
            }
        });

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
            clearStream(dialog);
            clearStream(raw);
            onlineCompletionTokens = 0;
            generating = false;
            tokenBar.show(false);
            addSystem(dialog, "── 对话已清空 ──");
            addSystem(raw, "── 对话已清空 ──");
        });

        // ---------- 原始指令流折叠区 ----------
        MaterialButton btnToggleRaw = findViewById(R.id.btn_toggle_raw);
        btnToggleRaw.setText(rawExpanded ? "原始指令流 ▴" : "原始指令流 ▾");
        btnToggleRaw.setOnClickListener(v -> {
            rawExpanded = !rawExpanded;
            panelRaw.setVisibility(rawExpanded ? View.VISIBLE : View.GONE);
            btnToggleRaw.setText(rawExpanded ? "原始指令流 ▴" : "原始指令流 ▾");
        });
        MaterialButton btnClearRaw = findViewById(R.id.btn_clear_raw);
        btnClearRaw.setOnClickListener(v -> {
            clearStream(raw);
            addSystem(raw, "── 原始流已清空 ──");
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

        addSystem(dialog, "── Agent 调试控制台已打开 ──");
        addSystem(raw, "── 等待外部注入数据…（此流仅显示外部传入的数据，不含执行过程）──");
        setPill("待触发", R.drawable.bg_pill_gray, "#94a3b8");
    }

    // ---------- 双流通用操作（chatkit 消息工厂 + ChatAdapter） ----------

    private void clearStream(StreamState s) {
        s.msgs.clear();
        s.adapter.notifyDataSetChanged();
        s.ai = null;
        s.think.setLength(0);
    }

    private void addUser(StreamState s, String text) {
        s.msgs.add(ChatMessage.createUserMessage(text));
        s.adapter.notifyItemInserted(s.msgs.size() - 1);
        scrollBottom(s);
    }

    private void addSystem(StreamState s, String text) {
        s.msgs.add(ChatMessage.createSystemMessage(text));
        s.adapter.notifyItemInserted(s.msgs.size() - 1);
        scrollBottom(s);
    }

    private void addAi(StreamState s, String text) {
        // 插入新 AI 消息前：刷新旧最后 AI（其 meta 隐藏，只留最终轮显示操作栏/统计/时间）
        int oldLastAi = s.adapter.findLastAiMessageIndex();
        s.msgs.add(ChatMessage.createAIMessage(text));
        s.adapter.notifyItemInserted(s.msgs.size() - 1);
        if (oldLastAi >= 0 && oldLastAi < s.msgs.size() - 1) s.adapter.notifyItemChanged(oldLastAi);
        scrollBottom(s);
    }

    private void addError(StreamState s, String text) {
        s.msgs.add(ChatMessage.createErrorMessage(text, null, false));
        s.adapter.notifyItemInserted(s.msgs.size() - 1);
        scrollBottom(s);
    }

    private void addToolCall(StreamState s, String name, String args) {
        s.msgs.add(ChatMessage.createToolCallMessage(UUID.randomUUID().toString(), name, args));
        s.adapter.notifyItemInserted(s.msgs.size() - 1);
        scrollBottom(s);
    }

    private void addToolResult(StreamState s, String name, String result) {
        String display;
        try {
            display = ToolResultInterpreter.formatForUi(name, result);
        } catch (Throwable t) {
            display = result;
        }
        s.msgs.add(ChatMessage.createToolResultMessage(name, display));
        s.adapter.notifyItemInserted(s.msgs.size() - 1);
        scrollBottom(s);
    }

    private void ensureAi(StreamState s) {
        if (s.ai == null) {
            s.ai = ChatMessage.createAIMessage("");
            s.msgs.add(s.ai);
            s.adapter.notifyItemInserted(s.msgs.size() - 1);
        }
    }

    private void appendAiToken(StreamState s, String token) {
        ensureAi(s);
        s.ai.content = (s.ai.content == null ? "" : s.ai.content) + token;
        s.adapter.notifyItemChanged(s.msgs.indexOf(s.ai), ChatAdapter.PAYLOAD_CONTENT_UPDATE);
        scrollBottom(s);
    }

    private void appendThinking(StreamState s, String token) {
        ensureAi(s);
        s.think.append(token);
        s.adapter.updateMessageThinkingContent(s.msgs.indexOf(s.ai), s.think.toString());
        scrollBottom(s);
    }

    private void scrollBottom(StreamState s) {
        s.rv.post(() -> {
            if (s.msgs.size() > 0) s.rv.scrollToPosition(s.msgs.size() - 1);
        });
    }

    // ---------- 事件解析（仅对话流；原始指令流只收 onRawEvent 外部数据） ----------

    private void applyEvent(StreamState s, String line) {
        if (line.startsWith("TOKEN: ")) {
            generating = true;
            synchronized (statLock) {
                onlineCompletionTokens++;
                long now = System.currentTimeMillis();
                float tps = 0;
                if (lastTokenTs > 0) {
                    long dt = now - lastTokenTs;
                    if (dt > 0) tps = 1000f / dt;
                }
                lastTokenTs = now;
                tokenBar.updateFromStreaming(onlineCompletionTokens, tps);
            }
            appendAiToken(s, line.substring(7).trim());
        } else if (line.startsWith("THINK: ")) {
            generating = true;
            appendThinking(s, line.substring(7).trim());
        } else if (line.startsWith("[STEP] ")) {
            generating = true;
            addSystem(s, "→ " + line.substring(7));
        } else if (line.startsWith("▶ ")) {
            generating = true;
            // 封口当前轮 AI 消息：工具调用前的模型输出独立成段（多轮回答树）
            // 下一轮 TOKEN 到达时 ensureAi 自动新开 AI 消息——每轮回答分开显示
            s.ai = null;
            s.think.setLength(0);
            String body = line.substring(2);
            int sp = body.indexOf(' ');
            addToolCall(s, sp > 0 ? body.substring(0, sp) : body, sp > 0 ? body.substring(sp + 1) : "");
        } else if (line.startsWith("✔ ")) {
            String body = line.substring(2);
            int arrow = body.indexOf("->");
            String name = arrow > 0 ? body.substring(0, arrow).trim() : body;
            String res = arrow > 0 ? body.substring(arrow + 2).trim() : "";
            addToolResult(s, name, res);
        } else if (line.contains("执行完成")) {
            generating = false;
            setPill("完成", R.drawable.bg_pill_green, "#6ee7b7");
            tokenBar.show(false);
            addSystem(s, "✅ 执行完成");
            s.ai = null;
            s.think.setLength(0);
        } else if (line.contains("执行出错") || line.contains("初始化失败")) {
            generating = false;
            setPill("错误", R.drawable.bg_pill_red, "#fca5a5");
            tokenBar.show(false);
            addError(s, line);
            s.ai = null;
            s.think.setLength(0);
        } else if (line.contains("思考结束")) {
            addSystem(s, "┄ 思考结束");
        } else if (line.startsWith("PROMPT: ")) {
            addUser(s, line.substring(8));
        } else {
            addSystem(s, line);
        }
    }

    // ---------- 本地智能体执行（AgentSession 标准接入，双流同喂） ----------

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
        dialog.think.setLength(0);
        dialog.ai = null;
        setPill("执行中", R.drawable.bg_pill_blue, "#93c5fd");
        tvConnState.setText("本地智能体执行中…");
        addUser(dialog, prompt);

        agentSession.setCallback(new AgentCallback() {
            @Override public void onToken(String token) {
                runOnUiThread(() -> {
                    synchronized (statLock) {
                        onlineCompletionTokens++;
                        long now = System.currentTimeMillis();
                        float tps = 0;
                        if (lastTokenTs > 0) {
                            long dt = now - lastTokenTs;
                            if (dt > 0) tps = 1000f / dt;
                        }
                        lastTokenTs = now;
                        tokenBar.updateFromStreaming(onlineCompletionTokens, tps);
                    }
                    appendAiToken(dialog, token);
                });
            }
            @Override public void onThinkingToken(String token) {
                runOnUiThread(() -> appendThinking(dialog, token));
            }
            @Override public void onThinkingEnd() {
                runOnUiThread(() -> addSystem(dialog, "┄ 思考结束"));
            }
            @Override public void onToolCallStart(String id, String name, String args) {
                runOnUiThread(() -> addToolCall(dialog, name, args));
            }
            @Override public void onToolCallComplete(String id, String name, OnlineToolResult result) {
                runOnUiThread(() -> addToolResult(dialog, name, String.valueOf(result)));
            }
            @Override public void onStepUpdate(String step, String detail) {
                runOnUiThread(() -> addSystem(dialog, "→ " + step + " " + detail));
            }
            @Override public void onComplete(String fullText) {
                runOnUiThread(() -> {
                    generating = false;
                    setPill("完成", R.drawable.bg_pill_green, "#6ee7b7");
                    tokenBar.show(false);
                    tvConnState.setText("✅ 本地执行完成");
                    addSystem(dialog, "✅ 执行完成");
                    dialog.ai = null;
                    dialog.think.setLength(0);
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    generating = false;
                    setPill("错误", R.drawable.bg_pill_red, "#fca5a5");
                    tokenBar.show(false);
                    tvConnState.setText("❌ 错误: " + error);
                    addError(dialog, "执行出错: " + error);
                    dialog.ai = null;
                    dialog.think.setLength(0);
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
            addSystem(dialog, "┄ 手动停止");
        } else {
            Toast.makeText(this, "当前无执行中的任务", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- UI 状态 ----------

    private void refreshBridgeState() {
        boolean on = AgentDebugBridge.isEnabled(this);
        switchBridge.setChecked(on);
        tvBridgeState.setText(on ? "已开启 · 外部广播可注入" : "已关闭 · 点击开启");
        tvToken.setText(AgentDebugBridge.getToken(this));
    }

    private void setPill(String text, int bgRes, String colorHex) {
        tvStatusPill.setText("● " + text);
        tvStatusPill.setBackgroundResource(bgRes);
        tvStatusPill.setTextColor(Color.parseColor(colorHex));
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
