package com.oilquiz.app.ui.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.AgentSession;
import com.oilquiz.app.ai.agent.ToolResultInterpreter;
import com.oilquiz.app.ai.agent.debug.DebugSpan;
import com.oilquiz.app.ai.agent.debug.DebugTracer;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;
import com.oilquiz.app.ai.chat.ChatAdapter;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.ui.TokenStatsBar;
import com.oilquiz.app.infra.AgentDebugBridge;
import com.oilquiz.app.ui.debug.DebugSpanAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Agent 调试控制台（v2 · 追踪瀑布版）
 *
 * 三大视图：
 *  1. 追踪瀑布：LLM/工具/思考/步骤的 Span 树，含每层耗时、token（输入/输出/缓存命中）与状态色；
 *  2. 对话流：chatkit 消息渲染（用户/AI/工具调用/思考/系统）；
 *  3. 原始流：本地执行 + 外部注入统一为 JSON 事件行
 *     （{t:token|think|tool_start|tool_end|llm_usage|run_start|run_end}），兼容旧格式与 [PROMPT]/[ANSWER]。
 *
 * 持久化：每次 run 结束写入 filesDir/debug_traces/runId.json，支持历史回放与 JSON 导出。
 * 本地智能体：AgentSession + DebugTracer（引擎侧 LLM 级 token/耗时钩子）。
 */
public class AgentDebugActivity extends AppCompatActivity implements TokenStatsBar.StatsSource {

    // ---------- 视图 ----------
    private SwitchMaterial switchBridge;
    private TextView tvBridgeState;
    private TextView tvToken;
    private TextView tvConnState;
    private TextView tvStatusPill;
    private TextView tvTokenStats;
    private EditText etPrompt;
    private TokenStatsBar tokenBar;
    private RecyclerView rvSpan, rvChat, rvRaw;
    private LinearLayout panelRaw;
    private MaterialButton tabSpan, tabChat, tabRaw, btnHistory;

    private static class StreamState {
        RecyclerView rv;
        ChatAdapter adapter;
        List<ChatMessage> msgs = new ArrayList<>();
        ChatMessage ai;
        StringBuilder think = new StringBuilder();
    }

    private final StreamState dialog = new StreamState();
    private final StreamState raw = new StreamState();

    // ---------- 追踪数据 ----------
    private final List<DebugSpan> spans = new ArrayList<>();
    private DebugSpanAdapter spanAdapter;
    private DebugSpan runRoot;
    private DebugSpan currentTool;
    private DebugSpan currentThinking;
    private String activeRunId;
    private long runStartMs;
    private String runModel = "";
    // 汇总
    private int sumPrompt, sumCompletion, sumCached, llmCount, toolCount;

    private AgentSession agentSession;
    private final Object statLock = new Object();
    private volatile boolean generating;
    private int onlineCompletionTokens;
    private long lastTokenTs;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final int VIEW_SPAN = 0, VIEW_CHAT = 1, VIEW_RAW = 2;

    // =====================================================================
    // DebugTracer：引擎侧 LLM 级 token / 耗时 / run 生命周期
    // =====================================================================
    private final DebugTracer tracer = new DebugTracer.NoOp() {
        @Override
        public void onRunStart(String runId, String model, long ts) {
            ui.post(() -> {
                if (activeRunId == null) {
                    beginRun(model);
                } else if (model != null && !model.isEmpty()) {
                    runModel = model;
                    if (runRoot != null) {
                        runRoot.name = model;
                        spanAdapter.notifySpanChanged();
                    }
                }
            });
        }

        @Override
        public void onLlmStart(String runId, String spanId, long ts) {
            ui.post(() -> {
                if (!isCurrentRun(runId)) return;
                DebugSpan s = new DebugSpan();
                s.id = spanId;
                s.runId = runId;
                s.parentId = activeRunId;
                s.type = DebugSpan.TYPE_LLM;
                s.name = runModel.isEmpty() ? "LLM 推理" : runModel;
                s.status = DebugSpan.STATUS_RUNNING;
                s.startMs = ts;
                s.depth = 1;
                addSpan(s);
            });
        }

        @Override
        public void onLlmUsage(String runId, String spanId, int promptTokens, int completionTokens,
                               int totalTokens, int cachedTokens, long startTs, long endTs) {
            ui.post(() -> {
                if (!isCurrentRun(runId)) return;
                DebugSpan s = findSpan(spanId);
                if (s == null) {
                    s = new DebugSpan();
                    s.id = spanId;
                    s.runId = runId;
                    s.parentId = activeRunId;
                    s.type = DebugSpan.TYPE_LLM;
                    s.name = runModel.isEmpty() ? "LLM 推理" : runModel;
                    s.depth = 1;
                    addSpan(s);
                }
                s.status = DebugSpan.STATUS_OK;
                s.startMs = startTs > 0 ? startTs : s.startMs;
                s.endMs = endTs;
                s.promptTokens = promptTokens;
                s.completionTokens = completionTokens;
                s.totalTokens = totalTokens;
                s.cachedTokens = cachedTokens;
                s.detail = "入 " + promptTokens + " · 出 " + completionTokens
                        + (cachedTokens > 0 ? " · 缓存命中 " + cachedTokens : "");
                sumPrompt += promptTokens;
                sumCompletion += completionTokens;
                sumCached += cachedTokens;
                llmCount++;
                spanAdapter.notifySpanChanged();
            });
        }

        @Override
        public void onRunEnd(String runId, String status, String error, long ts) {
            ui.post(() -> {
                if (!isCurrentRun(runId)) return;
                endRun(status, error);
            });
        }
    };

    private boolean isCurrentRun(String runId) {
        return runId != null && runId.equals(activeRunId);
    }

    private DebugSpan findSpan(String id) {
        if (id == null) return null;
        for (DebugSpan s : spans) {
            if (id.equals(s.id)) return s;
        }
        return null;
    }

    private void addSpan(DebugSpan s) {
        spans.add(s);
        spanAdapter.notifySpanChanged();
    }

    private void beginRun(String model) {
        activeRunId = UUID.randomUUID().toString();
        runStartMs = System.currentTimeMillis();
        runModel = model == null ? "" : model;
        spans.clear();
        sumPrompt = sumCompletion = sumCached = 0;
        llmCount = toolCount = 0;
        currentTool = currentThinking = null;

        DebugSpan root = new DebugSpan();
        root.id = activeRunId;
        root.runId = activeRunId;
        root.type = DebugSpan.TYPE_SESSION;
        root.name = runModel.isEmpty() ? "Agent 会话" : runModel;
        root.status = DebugSpan.STATUS_RUNNING;
        root.startMs = runStartMs;
        root.depth = 0;
        runRoot = root;
        addSpan(root);

        generating = true;
        setPill("执行中", R.drawable.bg_pill_blue, "#93c5fd");
        tvConnState.setText("执行中 · " + (runModel.isEmpty() ? "智能体" : runModel));
    }

    private void endRun(String status, String error) {
        if (activeRunId == null) return;
        if (runRoot != null) {
            runRoot.endMs = System.currentTimeMillis();
            runRoot.status = status;
            if (error != null && !error.isEmpty()) runRoot.error = error;
        }
        // 关闭所有 running 的叶子 span
        for (DebugSpan s : spans) {
            if (s.isRunning()) {
                s.endMs = System.currentTimeMillis();
                s.status = DebugSpan.STATUS_CANCELLED;
                if (DebugSpan.STATUS_ERROR.equals(status) && error != null) s.error = error;
            }
        }
        generating = false;

        StringBuilder sb = new StringBuilder();
        sb.append(llmCount > 0 ? llmCount + " 次 LLM" : "0 次 LLM");
        if (sumPrompt > 0 || sumCompletion > 0) {
            sb.append(" · 入 ").append(sumPrompt).append(" / 出 ").append(sumCompletion);
            if (sumCached > 0) sb.append(" / 缓存命中 ").append(sumCached);
        }
        sb.append(" · ").append(DebugSpanAdapter.fmtMs(System.currentTimeMillis() - runStartMs));
        String summary = sb.toString();

        if (DebugSpan.STATUS_OK.equals(status)) {
            setPill("完成", R.drawable.bg_pill_green, "#6ee7b7");
            tvConnState.setText("✅ " + summary);
        } else if (DebugSpan.STATUS_ERROR.equals(status)) {
            setPill("错误", R.drawable.bg_pill_red, "#fca5a5");
            tvConnState.setText("❌ " + (error == null ? "执行出错" : error));
        } else {
            setPill("已停止", R.drawable.bg_pill_gray, "#94a3b8");
            tvConnState.setText("◼ " + summary);
        }
        tokenBar.show(false);
        dialog.ai = null;
        raw.ai = null;

        saveTrace(status, error, summary);
        spanAdapter.notifySpanChanged();
        activeRunId = null;
    }

    // =====================================================================
    // 事件流（外部注入 + 兼容旧格式）
    // =====================================================================
    private final AgentDebugBridge.BridgeListener listener = new AgentDebugBridge.BridgeListener() {
        @Override
        public void onEvent(final String line) {
            runOnUiThread(() -> {
                if (line == null) return;
                String t = line.trim();
                if (t.startsWith("{")) {
                    if (!parseJsonEvent(t)) applyEvent(dialog, line);
                } else {
                    applyEvent(dialog, line);
                }
            });
        }

        @Override
        public void onRawEvent(final String line) {
            runOnUiThread(() -> {
                if (line == null) return;
                if (line.startsWith("[ANSWER] ")) {
                    addAi(raw, line.substring(9));
                } else if (line.startsWith("[PROMPT] ")) {
                    addUser(raw, line.substring(9));
                } else {
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

    // ---------- JSON 事件协议解析（本地/外部统一） ----------
    private boolean parseJsonEvent(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String t = o.optString("t", "");
            switch (t) {
                case "token": {
                    appendAiToken(dialog, o.optString("x", ""));
                    bumpToken();
                    addSystem(raw, json);
                    return true;
                }
                case "think": {
                    appendThinking(dialog, o.optString("x", ""));
                    addSystem(raw, json);
                    return true;
                }
                case "tool_start": {
                    String name = o.optString("n", "tool");
                    addToolCall(dialog, name, o.optString("a", ""));
                    addSystem(raw, json);
                    onToolSpanStart(o.optString("i", null), name, o.optString("a", ""));
                    return true;
                }
                case "tool_end": {
                    String name = o.optString("n", "tool");
                    addToolResult(dialog, name, o.optString("r", ""));
                    addSystem(raw, json);
                    onToolSpanEnd(o.optString("i", null), name, o.optString("r", ""), o.optLong("d", 0));
                    return true;
                }
                case "llm_usage": {
                    addSystem(raw, json);
                    onExternalLlmUsage(o.optInt("p", 0), o.optInt("c", 0),
                            o.optInt("t", 0), o.optInt("k", 0), o.optLong("d", 0));
                    return true;
                }
                case "run_start": {
                    beginRun(o.optString("m", "外部注入"));
                    addSystem(raw, json);
                    return true;
                }
                case "run_end": {
                    endRun(o.optString("s", "ok"), o.optString("e", ""));
                    addSystem(raw, json);
                    return true;
                }
                default:
                    return false;
            }
        } catch (Throwable th) {
            return false;
        }
    }

    /** 外部注入的 tool span（尽力而为，无父级关联时挂 session 下） */
    private void onToolSpanStart(String id, String name, String args) {
        if (id == null) return;
        DebugSpan s = new DebugSpan();
        s.id = id;
        s.runId = activeRunId == null ? "" : activeRunId;
        s.parentId = activeRunId;
        s.type = DebugSpan.TYPE_TOOL;
        s.name = name;
        s.status = DebugSpan.STATUS_RUNNING;
        s.startMs = System.currentTimeMillis();
        s.detail = args != null && args.length() > 80 ? args.substring(0, 80) + "…" : args;
        s.depth = 1;
        addSpan(s);
    }

    private void onToolSpanEnd(String id, String name, String result, long durationMs) {
        if (id == null) return;
        DebugSpan s = findSpan(id);
        if (s == null) {
            s = new DebugSpan();
            s.id = id;
            s.runId = activeRunId == null ? "" : activeRunId;
            s.parentId = activeRunId;
            s.type = DebugSpan.TYPE_TOOL;
            s.name = name;
            s.depth = 1;
            addSpan(s);
        }
        s.status = DebugSpan.STATUS_OK;
        s.endMs = System.currentTimeMillis();
        s.result = result != null && result.length() > 160 ? result.substring(0, 160) + "…" : result;
        toolCount++;
        spanAdapter.notifySpanChanged();
    }

    private void onExternalLlmUsage(int p, int c, int t, int k, long d) {
        sumPrompt += p;
        sumCompletion += c;
        sumCached += k;
        llmCount++;
        spanAdapter.notifySpanChanged();
    }

    // ---------- 旧格式事件解析（兼容 AgentDebugBridge 历史格式） ----------
    private void applyEvent(StreamState s, String line) {
        if (line.startsWith("TOKEN: ")) {
            bumpToken();
            appendAiToken(s, line.substring(7).trim());
        } else if (line.startsWith("THINK: ")) {
            appendThinking(s, line.substring(7).trim());
        } else if (line.startsWith("[STEP] ")) {
            addSystem(s, "→ " + line.substring(7));
        } else if (line.startsWith("▶ ")) {
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
            setPill("完成", R.drawable.bg_pill_green, "#6ee7b7");
            tokenBar.show(false);
            addSystem(s, "✅ 执行完成");
            s.ai = null;
            s.think.setLength(0);
        } else if (line.contains("执行出错") || line.contains("初始化失败")) {
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

    private void bumpToken() {
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
    }

    // =====================================================================
    // 生命周期
    // =====================================================================
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
        rvSpan = findViewById(R.id.rv_span);
        rvChat = findViewById(R.id.rv_chat);
        rvRaw = findViewById(R.id.rv_raw);
        tabSpan = findViewById(R.id.tab_span);
        tabChat = findViewById(R.id.tab_chat);
        tabRaw = findViewById(R.id.tab_raw);
        btnHistory = findViewById(R.id.btn_history);

        tokenBar = new TokenStatsBar(this, tvTokenStats, this);

        // ---------- 追踪瀑布 ----------
        spanAdapter = new DebugSpanAdapter(spans, this::showSpanDetail);
        rvSpan.setLayoutManager(new LinearLayoutManager(this));
        rvSpan.setAdapter(spanAdapter);
        rvSpan.setItemAnimator(null);

        // ---------- 对话流 / 原始流 ----------
        dialog.rv = rvChat;
        dialog.adapter = new ChatAdapter(dialog.msgs, action -> { });
        dialog.rv.setLayoutManager(new LinearLayoutManager(this));
        dialog.rv.setAdapter(dialog.adapter);
        dialog.rv.setItemAnimator(null);

        raw.rv = rvRaw;
        raw.adapter = new ChatAdapter(raw.msgs, action -> { });
        raw.rv.setLayoutManager(new LinearLayoutManager(this));
        raw.rv.setAdapter(raw.adapter);
        raw.rv.setItemAnimator(null);

        // ---------- Tab 切换 ----------
        tabSpan.setOnClickListener(v -> switchView(VIEW_SPAN));
        tabChat.setOnClickListener(v -> switchView(VIEW_CHAT));
        tabRaw.setOnClickListener(v -> switchView(VIEW_RAW));
        switchView(VIEW_SPAN);

        // ---------- 通道开关 / 复制 ----------
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

        // ---------- 清空 ----------
        MaterialButton btnClear = findViewById(R.id.btn_clear_log);
        btnClear.setOnClickListener(v -> {
            clearStream(dialog);
            clearStream(raw);
            spans.clear();
            activeRunId = null;
            onlineCompletionTokens = 0;
            generating = false;
            tokenBar.show(false);
            spanAdapter.notifySpanChanged();
            addSystem(dialog, "── 对话已清空 ──");
            addSystem(raw, "── 事件流已清空 ──");
        });
        MaterialButton btnClearRaw = findViewById(R.id.btn_clear_raw);
        btnClearRaw.setOnClickListener(v -> {
            clearStream(raw);
            addSystem(raw, "── 事件流已清空 ──");
        });

        // ---------- 历史 ----------
        btnHistory.setOnClickListener(v -> showHistoryDialog());

        // ---------- 本地智能体 ----------
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

        addSystem(dialog, "── Agent 调试控制台已打开（追踪瀑布视图） ──");
        addSystem(raw, "── 事件流就绪：本地执行与外部注入统一为 JSON 行 ──");
        setPill("待触发", R.drawable.bg_pill_gray, "#94a3b8");
    }

    private void switchView(int which) {
        rvSpan.setVisibility(which == VIEW_SPAN ? View.VISIBLE : View.GONE);
        rvChat.setVisibility(which == VIEW_CHAT ? View.VISIBLE : View.GONE);
        panelRaw.setVisibility(which == VIEW_RAW ? View.VISIBLE : View.GONE);
        tabSpan.setText((which == VIEW_SPAN ? "● " : "") + "追踪瀑布");
        tabChat.setText((which == VIEW_CHAT ? "● " : "") + "对话流");
        tabRaw.setText((which == VIEW_RAW ? "● " : "") + "原始流");
    }

    // =====================================================================
    // 消息工厂（chatkit）
    // =====================================================================
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

    // =====================================================================
    // 本地智能体执行（AgentSession + DebugTracer）
    // =====================================================================
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
        agentSession.setDebugTracer(tracer);
        onlineCompletionTokens = 0;
        lastTokenTs = 0;
        dialog.think.setLength(0);
        dialog.ai = null;
        raw.think.setLength(0);
        raw.ai = null;
        addUser(dialog, prompt);
        addUser(raw, prompt);
        beginRun("本地智能体");

        agentSession.setCallback(new AgentCallback() {
            @Override public void onToken(String token) {
                runOnUiThread(() -> {
                    bumpToken();
                    appendAiToken(dialog, token);
                    appendAiToken(raw, token);
                });
            }
            @Override public void onThinkingToken(String token) {
                runOnUiThread(() -> {
                    appendThinking(dialog, token);
                    appendThinking(raw, token);
                    if (currentThinking == null) {
                        DebugSpan s = new DebugSpan();
                        s.id = UUID.randomUUID().toString();
                        s.runId = activeRunId;
                        s.parentId = activeRunId;
                        s.type = DebugSpan.TYPE_THINKING;
                        s.name = "思考链";
                        s.status = DebugSpan.STATUS_RUNNING;
                        s.startMs = System.currentTimeMillis();
                        s.depth = 1;
                        currentThinking = s;
                        addSpan(s);
                    }
                });
            }
            @Override public void onThinkingEnd() {
                runOnUiThread(() -> {
                    addSystem(dialog, "┄ 思考结束");
                    if (currentThinking != null) {
                        currentThinking.endMs = System.currentTimeMillis();
                        currentThinking.status = DebugSpan.STATUS_OK;
                        spanAdapter.notifySpanChanged();
                        currentThinking = null;
                    }
                });
            }
            @Override public void onToolCallStart(String id, String name, String args) {
                runOnUiThread(() -> {
                    addToolCall(dialog, name, args);
                    DebugSpan s = new DebugSpan();
                    s.id = id != null ? id : UUID.randomUUID().toString();
                    s.runId = activeRunId;
                    s.parentId = activeRunId;
                    s.type = DebugSpan.TYPE_TOOL;
                    s.name = name;
                    s.status = DebugSpan.STATUS_RUNNING;
                    s.startMs = System.currentTimeMillis();
                    s.detail = args != null && args.length() > 80 ? args.substring(0, 80) + "…" : args;
                    s.depth = 1;
                    currentTool = s;
                    addSpan(s);
                });
            }
            @Override public void onToolCallComplete(String id, String name, OnlineToolResult result) {
                runOnUiThread(() -> {
                    String res = result == null ? "" : String.valueOf(result);
                    addToolResult(dialog, name, res);
                    if (currentTool != null) {
                        currentTool.endMs = System.currentTimeMillis();
                        currentTool.status = DebugSpan.STATUS_OK;
                        currentTool.result = res != null && res.length() > 160 ? res.substring(0, 160) + "…" : res;
                        toolCount++;
                        spanAdapter.notifySpanChanged();
                        currentTool = null;
                    }
                });
            }
            @Override public void onStepUpdate(String step, String detail) {
                runOnUiThread(() -> {
                    addSystem(dialog, "→ " + step + " " + detail);
                    DebugSpan s = new DebugSpan();
                    s.id = UUID.randomUUID().toString();
                    s.runId = activeRunId;
                    s.parentId = activeRunId;
                    s.type = DebugSpan.TYPE_STEP;
                    s.name = step;
                    s.status = DebugSpan.STATUS_OK;
                    s.startMs = System.currentTimeMillis();
                    s.endMs = s.startMs;
                    s.detail = detail;
                    s.depth = 1;
                    addSpan(s);
                });
            }
            @Override public void onComplete(String fullText) {
                runOnUiThread(() -> {
                    addSystem(dialog, "✅ 执行完成");
                    endRun(DebugSpan.STATUS_OK, null);
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> {
                    addError(dialog, "执行出错: " + error);
                    endRun(DebugSpan.STATUS_ERROR, error);
                });
            }
        });
        agentSession.start(prompt, 4096, true);
    }

    private void stopLocalAgent() {
        if (agentSession != null && agentSession.isBusy()) {
            agentSession.stop();
            addSystem(dialog, "┄ 手动停止");
            endRun(DebugSpan.STATUS_CANCELLED, null);
        } else {
            Toast.makeText(this, "当前无执行中的任务", Toast.LENGTH_SHORT).show();
        }
    }

    // =====================================================================
    // 持久化 / 历史 / 导出
    // =====================================================================
    private File traceDir() {
        File dir = new File(getFilesDir(), "debug_traces");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void saveTrace(String status, String error, String summary) {
        try {
            JSONObject root = new JSONObject();
            root.put("runId", activeRunId == null ? UUID.randomUUID().toString() : activeRunId);
            root.put("model", runModel);
            root.put("startMs", runStartMs);
            root.put("endMs", System.currentTimeMillis());
            root.put("status", status);
            root.put("error", error == null ? "" : error);
            root.put("summary", summary == null ? "" : summary);
            JSONArray arr = new JSONArray();
            for (DebugSpan s : spans) arr.put(new JSONObject(s.toJson()));
            root.put("spans", arr);
            File f = new File(traceDir(), "run_" + System.currentTimeMillis() + ".json");
            try (FileWriter w = new FileWriter(f)) {
                w.write(root.toString(2));
            }
        } catch (Throwable th) {
            // 落盘失败不影响调试
        }
    }

    private void showHistoryDialog() {
        File[] files = traceDir().listFiles((d, name) -> name.startsWith("run_") && name.endsWith(".json"));
        if (files == null || files.length == 0) {
            Toast.makeText(this, "暂无历史 trace（运行一次智能体后生成）", Toast.LENGTH_SHORT).show();
            return;
        }
        List<File> list = new ArrayList<>();
        Collections.addAll(list, files);
        list.sort(Comparator.comparingLong(File::lastModified).reversed());

        String[] items = new String[Math.min(list.size(), 20)];
        for (int i = 0; i < items.length; i++) {
            items[i] = describeTraceFile(list.get(i));
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("历史 trace（最近 " + items.length + " 条）")
                .setItems(items, (d, w) -> loadTrace(list.get(w)))
                .setNegativeButton("关闭", null)
                .show();
    }

    private String describeTraceFile(File f) {
        try (FileReader r = new FileReader(f)) {
            char[] buf = new char[4096];
            int n = r.read(buf);
            JSONObject o = new JSONObject(new String(buf, 0, n));
            String status = o.optString("status", "");
            String mark = "ok".equals(status) ? "✓" : ("error".equals(status) ? "✖" : "◼");
            return mark + " " + o.optString("model", "?")
                    + " · " + o.optString("summary", "")
                    + " · " + DebugSpanAdapter.fmtMs(Math.max(0, System.currentTimeMillis() - o.optLong("startMs", 0)))
                    + " 前";
        } catch (Throwable th) {
            return "? " + f.getName();
        }
    }

    private void loadTrace(File f) {
        try (FileReader r = new FileReader(f)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            JSONObject o = new JSONObject(sb.toString());
            spans.clear();
            runModel = o.optString("model", "");
            activeRunId = o.optString("runId", "");
            runStartMs = o.optLong("startMs", 0);
            JSONArray arr = o.optJSONArray("spans");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    DebugSpan s = DebugSpan.fromJson(arr.getJSONObject(i).toString());
                    if (DebugSpan.TYPE_SESSION.equals(s.type)) runRoot = s;
                    spans.add(s);
                }
            }
            spanAdapter.notifySpanChanged();
            switchView(VIEW_SPAN);
            setPill("历史回放", R.drawable.bg_pill_blue, "#93c5fd");
            tvConnState.setText("历史回放 · " + runModel + " · " + o.optString("summary", ""));
        } catch (Throwable th) {
            Toast.makeText(this, "加载失败: " + th.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void exportTrace(String runId) {
        File[] files = traceDir().listFiles((d, name) -> name.startsWith("run_") && name.endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            if (f.getName().contains(runId)) {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("application/json");
                send.putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", f));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.putExtra(Intent.EXTRA_SUBJECT, "Agent Debug Trace " + runId);
                startActivity(Intent.createChooser(send, "导出 trace JSON"));
                return;
            }
        }
        Toast.makeText(this, "未找到对应 trace 文件", Toast.LENGTH_SHORT).show();
    }

    // ---------- Span 详情 ----------
    private void showSpanDetail(DebugSpan s) {
        BottomSheetDialog dlg = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.dialog_span_detail, null);
        TextView title = v.findViewById(R.id.detail_title);
        TextView sub = v.findViewById(R.id.detail_sub);
        TextView meta = v.findViewById(R.id.detail_meta);
        TextView body = v.findViewById(R.id.detail_body);

        title.setText(DebugSpanAdapter.typeLabel(s.type) + " · " + (s.name == null ? "" : s.name));
        sub.setText("id: " + s.id + "\n父级: " + (s.parentId == null || s.parentId.isEmpty() ? "—" : s.parentId)
                + " · " + (s.isRunning() ? "运行中" : DebugSpanAdapter.fmtMs(s.durationMs())));
        StringBuilder m = new StringBuilder();
        m.append("状态: ").append(s.status);
        if (s.totalTokens > 0) {
            m.append("\ntoken: 入 ").append(s.promptTokens)
             .append(" / 出 ").append(s.completionTokens)
             .append(" / 总 ").append(s.totalTokens)
             .append(" / 缓存命中 ").append(s.cachedTokens);
        }
        meta.setText(m.toString());
        StringBuilder b = new StringBuilder();
        if (s.detail != null && !s.detail.isEmpty()) b.append("入参/说明:\n").append(s.detail).append("\n\n");
        if (s.result != null && !s.result.isEmpty()) b.append("结果:\n").append(s.result).append("\n\n");
        if (s.error != null && !s.error.isEmpty()) b.append("错误:\n").append(s.error).append("\n\n");
        if (b.length() == 0) b.append("（无附加内容）");
        body.setText(b.toString());

        final String runId = activeRunId != null ? activeRunId : s.runId;
        v.findViewById(R.id.btn_export_trace).setOnClickListener(ev -> {
            if (runId != null && !runId.isEmpty()) exportTrace(runId);
            else Toast.makeText(this, "当前无 run 上下文可导出", Toast.LENGTH_SHORT).show();
        });
        v.findViewById(R.id.btn_close_detail).setOnClickListener(ev -> dlg.dismiss());
        dlg.setContentView(v);
        dlg.show();
    }

    // =====================================================================
    // UI 状态
    // =====================================================================
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
    @Override public int getOnlinePromptTokens() { return sumPrompt; }
    @Override public float getPhaseSpeed() { return 0; }
    @Override public float getNativeInferenceSpeed() { return 0; }
    @Override public int getNativeTokenCount() { return 0; }
    @Override public String getGenPhase() { return null; }
    @Override public long getStreamingTokenCount() { return onlineCompletionTokens; }
    @Override public int getLastCacheHitTokens() { return sumCached; }
    @Override public int getLastPromptTokens() { return sumPrompt; }
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
