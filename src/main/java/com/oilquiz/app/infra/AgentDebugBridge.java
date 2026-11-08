package com.oilquiz.app.infra;

/*
 * ============================================================================
 * AgentDebugBridge —— 应用内 Agent 的正式外部注入通道（长期保留的 debug 能力）
 * ============================================================================
 * 用途：允许外部（adb shell）通过显式广播，注入 prompt 驱动应用内 Agent 执行一次
 *       任务，结果落盘供拉取。引擎**按当前使用的模型自动路由**（判据与聊天页同源：
 *       {@code InferenceRouter.isUsingOnlineModel()}）——在线模型走 OnlineAgentEngine
 *       （完整工具链），本地模型走 AgentSoftwareLayer（llama.cpp / NPU 由该层内部分档）。
 *       需要强制某一侧时显式传 --es engine online|local。用于自动化联动测试、外部编排、诊断。
 *
 * 安全设计（四层防护）：
 *   1. 授权码（token）：首次触发时随机生成 16 位 hex，存入 SharedPreferences，
 *      并在 logcat 打印（TAG=AgentDebugBridge，行含 "token="）。注入必须携带
 *      --es token <码>，不匹配直接拒绝。
 *   2. 通道开关（enabled）：默认 OFF。必须先带正确 token 发送 enable 广播打开；
 *      关闭状态下拒绝一切注入。开关状态持久化（install -r 保留）。
 *   3. 显式广播：Android 8+ 隐式广播无法触达静态 receiver，必须用 -n 指定组件；
 *      同时避免误伤系统广播分发。
 *   4. 执行隔离：在线分支每次独立引擎实例 + 随机会话 ID（历史独立文件），不污染主对话；
 *      并发互斥（同时仅一个任务）；120s 超时自动释放锁；只写结果文件，不碰业务数据。
 *      ⚠ 本地分支例外：AgentSoftwareLayer 进程内常驻（避免每次注入都重加载模型），
 *      多条注入**共享同一本地会话序列**（该层会 persist 自己的 session 计数），
 *      要彻底复位需重启应用。两条分支都不写用户主对话。
 *
 * 用法（本注释自包含；旧文档 DEBUG_AGENT_BRIDGE.md 只存于 _archive/，不在仓库根）：
 *   1) 拿 token（任意触发一次即可在 logcat 看到）：
 *        adb shell am broadcast -a com.oilquiz.app.DEBUG.AGENT_ENABLE \
 *            -n com.oilquiz.app/.infra.AgentDebugBridge --ez enable true
 *      然后 logcat -s AgentDebugBridge 查看 token=xxxxxxxxxxxxxxxx
 *   2) 打开通道：
 *        adb shell am broadcast -a com.oilquiz.app.DEBUG.AGENT_ENABLE \
 *            -n com.oilquiz.app/.infra.AgentDebugBridge \
 *            --es token <token> --ez enable true
 *   3) 注入执行：
 *        adb shell am broadcast -a com.oilquiz.app.DEBUG.AGENT_EXEC \
 *            -n com.oilquiz.app/.infra.AgentDebugBridge \
 *            --es token <token> --es prompt "指令" [--es session 会话id] \
 *            [--ei max_tokens 16384] [--ez thinking true] \
 *            [--es engine auto|local|online]   # 缺省 auto=按当前模型路由，后两者强制
 *   4) 结果：/sdcard/Android/data/com.oilquiz.app/files/agent_bridge/result_<ts>.txt
 *      关闭通道：--ez enable false
 * ============================================================================
 */
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.software.AgentSoftwareLayer;
import com.oilquiz.app.ai.agent.software.model.AgentResponse;
import com.oilquiz.app.ai.agent.online.OnlineAgentEngine;
import com.oilquiz.app.ai.agent.online.OnlineToolManager;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.service.AIService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class AgentDebugBridge extends BroadcastReceiver {

    private static final String TAG = "AgentDebugBridge";
    /** 执行注入 action */
    public static final String ACTION_EXEC = "com.oilquiz.app.DEBUG.AGENT_EXEC";
    /** 开关控制 action */
    public static final String ACTION_ENABLE = "com.oilquiz.app.DEBUG.AGENT_ENABLE";

    private static final String PREFS = "agent_bridge";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_ENABLED = "enabled";
    /** 默认最大 token（引擎内部还会与 MAX_TOKENS 取较大值） */
    private static final int DEFAULT_MAX_TOKENS = 8192;
    /** 任务超时（毫秒）：超时只释放并发锁，不强制中断引擎 */
    private static final long TASK_TIMEOUT_MS = 120_000L;

    private static final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 本地引擎实例（auto 路由到本地、或显式 engine=local 时复用）：本机实测模型
     * 冷加载 7.4s、native 状态恢复重载 13.3s，故进程内常驻避免反复加载。
     * 走 AgentSoftwareLayer（聊天页 "Local model → AgentSoftwareLayer" 同源，
     * NPU/llama.cpp 由该层内部分档）—— 不是 AgentSession：AgentSession 内部其实是
     * OnlineAgentEngine，调试台把它标成"本地智能体"是措辞误导。
     */
    private static volatile AgentSoftwareLayer sLocalLayer;
    /** 本地注入的上一次 session id：本地层是常驻的，换 session 必须清会话序列，否则串上下文。 */
    private static volatile String sLastLocalSessionId;

    // ---------------- 状态管理（SharedPreferences 持久化） ----------------

    /** 获取/首次生成授权码（16 位 hex，随机） */
    public static String getToken(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String t = sp.getString(KEY_TOKEN, null);
        if (t == null) {
            byte[] b = new byte[8];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                sb.append(String.format(Locale.US, "%02x", x));
            }
            t = sb.toString();
            sp.edit().putString(KEY_TOKEN, t).apply();
            Log.i(TAG, "token=" + t + " (首次生成，已持久化；请妥善保管)");
        }
        return t;
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    // ---------------- 调试控制台事件总线（同进程，Activity 订阅） ----------------

    /** 调试页监听器：onEvent 为日志行，onState 为状态字段（connection/status），
     *  onRawEvent 为【外部传入数据】专用（注入指令/参数/校验/回执，不含 agent 输出） */
    public interface BridgeListener {
        void onEvent(String line);
        void onState(String key, String value);
        default void onRawEvent(String line) {}
    }

    private static volatile BridgeListener listener;

    // 事件缓冲：调试台 Activity 打开（listener 注册）前的注入事件暂存于此，
    // setListener 时按序回放——避免注入开头的 [INJECT]/[PROMPT] 等事件因 Activity 未就绪而丢失
    private static final java.util.List<String> pendingEvents = new java.util.ArrayList<>();
    private static final java.util.List<String> pendingRaw = new java.util.ArrayList<>();
    private static final java.util.List<String[]> pendingStates = new java.util.ArrayList<>();

    /** 注册/注销调试页监听（Activity onResume/onPause 调用） */
    public static void setListener(BridgeListener l) {
        listener = l;
        if (l != null) {
            for (String[] s : pendingStates) l.onState(s[0], s[1]);
            pendingStates.clear();
            for (String e : pendingEvents) l.onEvent(e);
            pendingEvents.clear();
            for (String r : pendingRaw) l.onRawEvent(r);
            pendingRaw.clear();
        }
    }

    /** 推送日志行到调试页 */
    private static void emit(String line) {
        BridgeListener l = listener;
        if (l != null) {
            l.onEvent(line);
        } else {
            pendingEvents.add(line);
            trimPending();
        }
    }

    /** 推送【外部传入数据】到调试页原始指令流（注入元数据/校验/回执，不含执行过程） */
    private static void emitRaw(String line) {
        BridgeListener l = listener;
        if (l != null) {
            l.onRawEvent(line);
        } else {
            pendingRaw.add(line);
            trimPending();
        }
    }

    /** 推送状态到调试页 */
    private static void emitState(String key, String value) {
        BridgeListener l = listener;
        if (l != null) {
            l.onState(key, value);
        } else {
            pendingStates.add(new String[]{key, value});
            trimPending();
        }
    }

    /** 缓冲上限保护（防泄漏） */
    private static void trimPending() {
        while (pendingEvents.size() > 200) pendingEvents.remove(0);
        while (pendingRaw.size() > 200) pendingRaw.remove(0);
        while (pendingStates.size() > 50) pendingStates.remove(0);
    }

    /** 自动跳转到调试控制台 */
    private static void openDebugActivity(Context app) {
        try {
            Intent i = new Intent(app, com.oilquiz.app.ui.activity.AgentDebugActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            app.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "open debug activity failed: " + e.getMessage());
        }
    }

    // ---------------- 广播入口 ----------------

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        Context app = context.getApplicationContext();

        // 安全校验：授权码（任何 action 都必须携带）
        String expected = getToken(app);
        String provided = intent.getStringExtra("token");
        if (expected == null || !expected.equals(provided)) {
            Log.w(TAG, "拒绝: token 不匹配 (action=" + action + ")");
            emitRaw("[REJECT] token 不匹配 (action=" + action + ") " + tsStamp());
            return;
        }

        if (ACTION_ENABLE.equals(action)) {
            boolean on = intent.getBooleanExtra("enable", false);
            setEnabled(app, on);
            Log.i(TAG, "通道开关 -> " + (on ? "ON" : "OFF"));
            emitRaw("[ENABLE] 外部注入通道 -> " + (on ? "ON" : "OFF") + " " + tsStamp());
            return;
        }

        if (!ACTION_EXEC.equals(action)) return;
        if (!isEnabled(app)) {
            Log.w(TAG, "拒绝: 通道未开启（先发 AGENT_ENABLE enable=true）");
            emitRaw("[REJECT] 通道未开启（先发 AGENT_ENABLE enable=true） " + tsStamp());
            return;
        }

        String prompt = intent.getStringExtra("prompt");
        if (prompt == null || prompt.trim().isEmpty()) {
            Log.w(TAG, "拒绝: prompt 为空");
            emitRaw("[REJECT] prompt 为空 " + tsStamp());
            return;
        }
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "拒绝: 已有任务执行中");
            emitRaw("[REJECT] 已有任务执行中 " + tsStamp());
            return;
        }

        final PendingResult pr = goAsync();
        final String session = intent.getStringExtra("session");
        final int maxTokens = intent.getIntExtra("max_tokens", DEFAULT_MAX_TOKENS);
        final boolean thinking = intent.getBooleanExtra("thinking", false);
        // 引擎路由：缺省/auto = 按当前使用的模型自动选（判据与聊天页 shouldUseOnlineModel 同源）；
        // 显式 engine=online / engine=local 可强制某一侧。
        final String engineArg = intent.getStringExtra("engine");
        final boolean forceOnline = "online".equalsIgnoreCase(engineArg);
        final boolean forceLocal = "local".equalsIgnoreCase(engineArg);
        boolean autoLocal = false;
        if (!forceOnline && !forceLocal) {
            try {
                autoLocal = !InferenceRouter.getInstance(app).isUsingOnlineModel();
            } catch (Throwable t) {
                Log.w(TAG, "自动路由判据不可用，回落在线: " + t);
            }
        }
        final boolean useLocalEngine = forceLocal || autoLocal;
        Log.i(TAG, "引擎路由: engine=" + (engineArg == null || engineArg.isEmpty() ? "auto" : engineArg)
                + " → " + (useLocalEngine ? "本地(AgentSoftwareLayer)" : "在线(OnlineAgentEngine)"));
        // 防蒸馏：本通道定位为【正式文本通道】——prompt 为任务正式文本，**默认完整可见**。
        // 外部若注入提示词性质内容，可传 prompt_summary 声明敏感 → 我方只显示摘要（防蒸馏模式）。
        // expose_prompt=true 强制完整回显（即使传了 summary）。
        final String summary = intent.getStringExtra("prompt_summary");
        final boolean exposePrompt = intent.getBooleanExtra("expose_prompt", false);
        final boolean hasSummary = summary != null && !summary.trim().isEmpty();
        final boolean fullVisible = exposePrompt || !hasSummary;
        final String displayPrompt = fullVisible ? prompt : summary.trim();
        final String promptType = fullVisible ? "full" : "summary";
        Log.i(TAG, "prompt 已接收（" + prompt.length() + " 字符），正式文本默认完整可见；"
                + "如需防蒸馏请外部传 prompt_summary（当前模式: " + (hasSummary && !exposePrompt ? "摘要" : "完整可见") + "）");
        final String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        final File outDir = new File(app.getExternalFilesDir(null), "agent_bridge");
        outDir.mkdirs();
        final File out = new File(outDir, "result_" + ts + ".txt");
        final File debugOut = new File(outDir, "debug_" + ts + ".json");
        final String sessionId = (session == null || session.isEmpty()) ? "ext_" + ts : session;
        // 关键：logcat-capture 线程与引擎回调线程都会写这里，必须用线程安全容器；
        // 之前用 StringBuilder，收尾时两边并发 append 可能抛 StringIndexOutOfBounds 或丢尾部日志。
        final StringBuffer sb = new StringBuffer();
        final JSONObject startSnapshot = AgentDebugSnapshot.capture(app);
        final long taskStartMs = System.currentTimeMillis();
        final int promptTokens = countTokensSafe(prompt);
        final TaskMeta meta = new TaskMeta(app, out, debugOut, sb, displayPrompt, promptType,
                useLocalEngine ? "local" : "online", sessionId, taskStartMs, promptTokens, startSnapshot);

        // 自动跳转调试控制台（实时显示连通状态与执行过程）
        openDebugActivity(app);
        emitState("connection", "注入已接收，正在启动引擎…");
        emit("── 外部注入接收 ──");
        // 对话流：默认只显示摘要/占位，不回显完整指令（防蒸馏）
        emit(fullVisible ? "PROMPT: " + prompt : "PROMPT_SUMMARY: " + summary.trim());

        // 【外部传入数据】原始指令流：注入元数据 + 摘要点/提示词（不含 agent 输出）
        String promptOneLine = prompt.replace('\n', ' ').trim();
        emitRaw("[INJECT] action=AGENT_EXEC token=OK "
                + "session=" + (session == null || session.isEmpty() ? "ext_" + ts : session)
                + " max_tokens=" + maxTokens
                + " thinking=" + thinking
                + " " + tsStamp());
        if (hasSummary) {
            emitRaw("[SUMMARY] " + summary.trim());                     // 外部声明敏感：只显示摘要（防蒸馏模式）
        } else {
            emitRaw("[PROMPT] " + promptOneLine);                       // 正式文本默认完整可见
        }

        // ===== 启动 logcat 监听：捕获引擎详细日志，追加到结果文件 =====
        final Process[] logcatProc = {null};
        try {
            // -T：只从本任务开始时刻抓，避免把历史缓冲区里的旧日志混进本次结果；
            // 多抓 SystemProbe/ContextWindowManager/ResourceConfig，探针读数才能和引擎日志对上。
            final String logcatSince = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            logcatProc[0] = Runtime.getRuntime().exec(
                new String[]{"logcat", "-v", "time", "-T", logcatSince, "-s",
                    "OnlineAgentEngine:V", "OnlinePromptBuilder:V",
                    "OnlineToolManager:V", "AIService:V",
                    "OnlineInferenceService:V",
                    // auto 路由到本地时，结果文件此前只能看到在线那半边的轨迹：
                    // 本地引擎/原生层的标签根本不在过滤器里
                    "AgentSoftwareLayer:V", "AgentLoopEngine:V",
                    "LlamaHelper:V", "LlamaJNI:V", "AgentKvCache:V",
                    "SystemProbe:V", "ContextWindowManager:V", "ResourceConfig:V",
                    "AgentDebugBridge:V"});
            final java.io.BufferedReader logReader = new java.io.BufferedReader(
                new java.io.InputStreamReader(logcatProc[0].getInputStream(), StandardCharsets.UTF_8));
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        String line;
                        while ((line = logReader.readLine()) != null) {
                            sb.append("[LOG] ").append(line).append('\n');
                        }
                    } catch (Exception ignored) {}
                }
            }, "logcat-capture").start();
        } catch (Exception e) {
            Log.w(TAG, "logcat capture start failed: " + e.getMessage());
        }

        // 广播令牌只覆盖"接收 + 启动"这段：一条生成本身远超广播的 60s 上限
        // （真机实测 ANR Timeout=60216ms，生成线程停在 LlamaHelper.chatJson 等 native 读写锁；
        // 本地档还多加一次模型冷加载 7~14s）。攥着 PendingResult 到生成结束必然报广播超时 ANR，
        // 所以 kickoff 完成即交还；进程由自动打开的调试控制台保持前台，后续写文件不受影响。
        final AtomicBoolean tokenReleased = new AtomicBoolean(false);
        final Runnable releaseToken = () -> {
            if (tokenReleased.compareAndSet(false, true)) {
                try { pr.finish(); } catch (Throwable ignored) { }
            }
        };

        // 收尾一次性闸门：完成/失败/超时只允许 finish 一次。
        // 此前超时回调没有绑定"本次任务是否已收尾"，120s 到点会对已 finish 的 PendingResult
        // 再 finish 一次 → IllegalStateException: Broadcast already finished（真机 2026-10-27 19:53 崩过）。
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Runnable finishOnce = () -> {
            if (finished.compareAndSet(false, true)) {
                if (logcatProc[0] != null) logcatProc[0].destroy();
                running.set(false);
                releaseToken.run();
            }
        };

        try {
            final int[] attempt = {0};
            final boolean[] retrying = {false};
            final String sid = sessionId;
            // 复用同一引擎实例重试：保留 messageHistory/thinkingChain，断点续传（近似）
            final OnlineToolManager[] tmHolder = {null};
            final OnlineAgentEngine[] engineHolder = {null};
            final AgentSoftwareLayer[] localHolder = {null};

            final Runnable[] executeTask = new Runnable[1];
            executeTask[0] = new Runnable() {
                @Override public void run() {
                    attempt[0]++;
                    // 每轮重新计数已由 TaskMeta.markFirstToken/计数自然累积；零输出重连不丢统计
                    if (useLocalEngine) {
                        // 本地引擎常驻复用：模型冷加载数秒~十余秒，且同实例保留 KV/历史
                        if (localHolder[0] == null) {
                            AgentSoftwareLayer layer = sLocalLayer;
                            if (layer == null) {
                                layer = new AgentSoftwareLayer(app, AIService.getInstance(app));
                                sLocalLayer = layer;
                            }
                            localHolder[0] = layer;
                        }
                    } else if (engineHolder[0] == null) {
                        tmHolder[0] = new OnlineToolManager(app);
                        OnlineToolManager.setInstance(tmHolder[0]);
                        engineHolder[0] = new OnlineAgentEngine(app, tmHolder[0]);
                        engineHolder[0].setSessionId(sid);
                    }
                    emitState("connection", (useLocalEngine
                            ? "本地引擎就绪(AgentSoftwareLayer → llama.cpp)"
                            : "在线引擎就绪(OnlineAgentEngine)")
                            + "，会话隔离=" + (useLocalEngine ? "进程内常驻(共享本地会话)" : sid)
                            + (attempt[0] > 1 ? "（第 " + attempt[0] + " 次尝试，断点续传）" : ""));

                    AgentCallback cb = new AgentCallback() {
                        @Override public void onToken(String token) {
                            meta.contentTokens++;
                            meta.markFirstToken();
                            sb.append("TOKEN: ").append(token).append('\n');
                            emit("TOKEN: " + token);
                        }
                        @Override public void onThinkingToken(String token) {
                            meta.thinkingTokens++;
                            meta.markFirstToken();
                            sb.append("THINK: ").append(token).append('\n');
                            emit("THINK: " + token);
                        }
                        @Override public void onThinkingEnd() {
                            sb.append("-- think end --\n");
                            emit("── 思考结束 ──");
                        }
                        @Override public void onToolCallStart(String id, String name, String args) {
                            meta.toolStart();
                            sb.append("[TOOL] ").append(name).append(' ').append(args).append('\n');
                            emit("▶ 工具调用: " + name + " " + args);
                        }
                        @Override public void onToolCallComplete(String id, String name, OnlineToolResult r) {
                            meta.toolEnd(name, true, String.valueOf(r));
                            sb.append("[TOOL-OK] ").append(name).append(" -> ").append(r).append('\n');
                            emit("✔ 工具完成: " + name + " -> " + r);
                        }
                        @Override public void onStepUpdate(String step, String detail) {
                            sb.append("[STEP] ").append(step).append(' ').append(detail).append('\n');
                            emit("[STEP] " + step + " " + detail);
                        }
                        @Override public void onComplete(String fullText) {
                            meta.error = null;
                            sb.append("=== COMPLETE ===\n").append(fullText).append('\n');
                            emitState("status", "✅ 完成");
                            emit("── 执行完成 ──");
                            emitRaw("[RESULT] done result=" + out.getName() + " " + tsStamp());
                            emitRaw("[ANSWER] " + oneLine(fullText, 300));
                            save("done", fullText); Log.i(TAG, "done -> " + out); release();
                        }
                        @Override public void onError(String error) {
                            meta.error = error;
                            sb.append("=== ERROR ===\n").append(error).append('\n');
                            emitState("status", "❌ 错误: " + error);
                            emit("── 执行出错: " + error + " ──");
                            if (meta.totalTokens() > 0) {
                                // 已有输出（思考/正文任意 token）：连接是通的，中断属流式瞬时故障。
                                // 不粗暴判为网络问题重试，保留已生成内容按「部分完成」收尾。
                                String partialTail = sb.length() > 0
                                        ? sb.substring(Math.max(0, sb.length() - 600))
                                        : "";
                                sb.append("=== PARTIAL ===\n")
                                    .append("连接中断，已保留部分输出（已输出 token=").append(meta.totalTokens()).append("）\n");
                                emitState("status", "⚠️ 连接中断，已保留部分输出");
                                emit("── 连接中断，已保留部分输出 ──");
                                emitRaw("[RESULT] partial result=" + out.getName() + " " + tsStamp());
                                emitRaw("[ANSWER] 部分输出：" + oneLine(partialTail, 300));
                                Log.w(TAG, "partial (tokens=" + meta.totalTokens() + ", err=" + error + ") -> " + out);
                                save("partial", "连接中断，已保留部分输出（token=" + meta.totalTokens() + "）。\n"
                                        + "已生成尾部：" + partialTail.trim());
                                release();
                            } else if (attempt[0] < MAX_RETRY && isNetworkError(error)) {
                                // 零输出 + 网络类错误：连接尚未建立/首个 token 前失败，才走重连
                                Log.w(TAG, "network error, auto retry " + attempt[0] + "/" + MAX_RETRY + ": " + error);
                                emitState("status", "⏳ 网络中断，3 秒后自动重连（第 " + (attempt[0] + 1) + " 次）");
                                emit("── 网络中断，3 秒后自动重连（第 " + (attempt[0] + 1) + " 次）──");
                                emitRaw("[RETRY] 网络错误，3 秒后自动重连（第 " + (attempt[0] + 1) + " 次） " + tsStamp());
                                retrying[0] = true;
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    retrying[0] = false;
                                    executeTask[0].run();
                                }, RETRY_DELAY_MS);
                            } else {
                                emitRaw("[RESULT] error result=" + out.getName() + " " + tsStamp());
                                save("error", "推理失败: " + error); Log.e(TAG, "error: " + error + " -> " + out); release();
                            }
                        }
                        void save(String status, String resultText) {
                            writeTaskArtifacts(meta, status, resultText);
                        }
                        void release() {
                            finishOnce.run();
                        }
                    };

                    // 本地引擎回调适配：与在线通道共用同一套落盘/状态约定
                    final AgentSoftwareLayer.AgentCallback localCb = new AgentSoftwareLayer.AgentCallback() {
                        @Override public void onToken(String token) {
                            meta.contentTokens++;
                            meta.markFirstToken();
                            sb.append("TOKEN: ").append(token).append('\n');
                            emit("TOKEN: " + token);
                        }
                        @Override public void onThinkingUpdate(String thought) {
                            meta.thinkingTokens++;
                            meta.markFirstToken();
                            sb.append("THINK: ").append(thought).append('\n');
                            emit("THINK: " + thought);
                        }
                        @Override public void onStepUpdate(String step, String detail) {
                            sb.append("[STEP] ").append(step).append(' ').append(detail).append('\n');
                            emit("[STEP] " + step + " " + detail);
                        }
                        @Override public void onToolCallStart(String name, String args) {
                            meta.toolStart();
                            sb.append("[TOOL] ").append(name).append(' ').append(args).append('\n');
                            emit("▶ 工具调用: " + name + " " + args);
                        }
                        @Override public void onToolCallComplete(String name, boolean ok, String result) {
                            meta.toolEnd(name, ok, result);
                            sb.append(ok ? "[TOOL-OK] " : "[TOOL-FAIL] ").append(name)
                              .append(" -> ").append(result).append('\n');
                            emit((ok ? "✔ 工具完成: " : "✖ 工具失败: ") + name + " -> " + oneLine(result, 200));
                        }
                        @Override public void onInferenceProgress(int tokenCount, float tps) {
                            // coopmat A/B 的主测点：解码速率随生成过程持续记录，供两组直接对照
                            sb.append("[SPEED] tokens=").append(tokenCount).append(" tps=")
                              .append(String.format(java.util.Locale.US, "%.2f", tps)).append('\n');
                        }
                        @Override public void onComplete(AgentResponse response) {
                            String fullText = (response != null && response.finalAnswer != null)
                                    ? response.finalAnswer : "";
                            meta.error = null;
                            sb.append("=== COMPLETE ===\n").append(fullText).append('\n');
                            emitState("status", "✅ 完成(本地)");
                            emit("── 执行完成 ──");
                            emitRaw("[RESULT] done result=" + out.getName() + " " + tsStamp());
                            emitRaw("[ANSWER] " + oneLine(fullText, 300));
                            saveLocal("done", fullText);
                            finishOnce.run();
                        }
                        @Override public void onError(String error) {
                            meta.error = error;
                            sb.append("=== ERROR ===\n").append(error).append('\n');
                            emitState("status", "❌ 错误(本地): " + error);
                            emit("── 本地执行出错: " + error + " ──");
                            emitRaw("[RESULT] error result=" + out.getName() + " " + tsStamp());
                            saveLocal("error", "本地推理失败: " + error);
                            finishOnce.run();
                        }
                        @Override public void onBusy(String msg) {
                            meta.error = "busy: " + msg;
                            emit("── 本地引擎忙，本次注入未执行 ──");
                            saveLocal("busy", "本地引擎忙，未执行");
                            finishOnce.run();
                        }
                        void saveLocal(String status, String text) {
                            writeTaskArtifacts(meta, status, text);
                        }
                    };

                    if (localHolder[0] != null) {
                        localHolder[0].setCallback(localCb);
                    } else {
                        engineHolder[0].setCallback(cb);
                    }
                    Log.i(TAG, "execute#" + attempt[0] + ": " + prompt + " (engine=" + (useLocalEngine ? "local" : "online") + ", session=" + sid + ", maxTokens=" + maxTokens + ", thinking=" + thinking + ")");
                    sb.append(fullVisible ? "PROMPT: " : "PROMPT_SUMMARY: ")
                            .append(displayPrompt).append('\n');
                    writeRunningStatus(meta);
                    // 外部注入模式：加系统提示前缀，优化行为
            String effectivePrompt = "[调试模式] 你现在处于外部调试通道。请：\n"
                    + "1. 直接输出完整答案，不要反问用户（如\"你要出门吗？\"）\n"
                    + "2. 不要使用 ui_component 工具，直接输出文本答案\n"
                    + "3. 减少不必要的工具调用，简单问题直接回答\n\n"
                    + "用户指令：" + prompt;
            if (localHolder[0] != null) {
                // 本地层入口是 processMessage(...)（自带 Agent 循环，maxTokens 由服务层/模型档决定）。
                // 注入通道没有聊天页那套 ensureModelLoaded，所以未就绪时先异步加载；
                // 直接在 onReceive(主线程) 调 initialize() 会 ANR（本机实测原生加载约 7–14s）。
                // 判据必须是 **native chat context 是否有效**（与聊天页 5169 同一判据），
                // 不是 AIService.isInitialized()：实测它已经 true 时 chatJson 仍报
                // "Helper context not initialized"、10ms 返回兜底句 —— 那是假成功，
                // 会把"引擎根本没跑"读成"模型答不出来"。
                final AIService aiService = AIService.getInstance(app);
                // 本地就绪判据必须是 **chat context 已建立**（getContextSize()>0），不是
                // isNativeStateValid()：真机复现，模型还在加载时 isNativeStateValid 已为 true，
                // 此时 AgentLoopEngine 读到 native=0，用 4096 兜底门把 3720 token 的 prompt
                // 拒成 "Prompt过长: 3720 >= 3584"，调试桥就会把"引擎没就绪"记成"模型答不了"。
                if (!isLocalReady()) {
                    emitState("connection", "本地引擎未就绪（chat context 未建立），等待加载…");
                    emit("── 等待本地模型/chat context 就绪 ──");
                    aiService.initializeAsync(ok -> {
                        if (!ok) {
                            localCb.onError("本地引擎初始化失败: initializeAsync=false");
                            return;
                        }
                        waitForLocalReady(localHolder[0], effectivePrompt, thinking, localCb, meta, 0);
                    });
                } else {
                    meta.refreshStartSnapshot();
                    meta.markReady();
                    prepareLocalSession(localHolder[0], meta.session);
                    localHolder[0].processMessage(effectivePrompt, thinking);
                }
            } else {
                meta.markReady();
                engineHolder[0].execute(effectivePrompt, maxTokens, thinking);
            }
            // 已提交给引擎（或已提交异步加载），广播使命结束 → 立刻交还令牌，
            // 避免生成跑过 60s 被判广播超时 ANR。
            releaseToken.run();
                }
            };

            executeTask[0].run();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                // 本任务已收尾或正在重连：不碰上一轮的 PendingResult（否则会二次 finish 崩溃）
                if (finished.get() || retrying[0]) return;
                Log.w(TAG, "timeout " + TASK_TIMEOUT_MS + "ms, 释放并发锁");
                emitState("status", "⏱ 超时释放并发锁");
                emitRaw("[RESULT] timeout result=" + out.getName() + " " + tsStamp());
                writeTaskArtifacts(meta, "timeout", "任务超时（120s），已释放并发锁，结果不完整");
                finishOnce.run();
            }, TASK_TIMEOUT_MS);
        } catch (Throwable t) {
            Log.e(TAG, "init fail", t);
            sb.append("INIT ERROR: ").append(t).append('\n');
            emitState("status", "❌ 初始化失败: " + t);
            emit("── 初始化失败: " + t + " ──");
            emitRaw("[RESULT] init_error " + t + " " + tsStamp());
            meta.error = "init: " + t;
            writeTaskArtifacts(meta, "error", "初始化失败: " + t);
            finishOnce.run();
        }
    }

    /** 网络类瞬时故障自动重连：次数上限与间隔 */
    private static final int MAX_RETRY = 3;
    private static final long RETRY_DELAY_MS = 5000L;

    /** 判断是否为可自动重连的网络类错误 */
    private static boolean isNetworkError(String err) {
        if (err == null) return false;
        String e = err.toLowerCase();
        return e.contains("connection") || e.contains("abort") || e.contains("socket")
                || e.contains("timeout") || e.contains("网络") || e.contains("无法连接")
                || e.contains("connect") || e.contains("reset") || e.contains("ioexception")
                || e.contains("interrupted") || e.contains("dns") || e.contains("unreachable");
    }

    /** 本地就绪 = native context 有效 **且** chat context 已建立（getContextSize>0）。 */
    private static boolean isLocalReady() {
        try {
            return com.oilquiz.app.ai.jni.LlamaHelper.isNativeStateValid()
                    && com.oilquiz.app.ai.jni.LlamaHelper.getContextSize() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * BRIDGE-SESSION(2026-11-08)：session 参数对本地层也要生效。
     *
     * <p>AgentSoftwareLayer 是常驻单例，AgentLoopEngine.sessionMessages 会跨任务保留；
     * 之前 bridge 只把 session 写进 status/debug，本地层完全没消费它，于是
     * session=A 的问句会带进 session=B 的历史，既污染回答又让 prompt 变大。
     * 换 session 时清一次会话序列；同 session 保留多轮上下文。</p>
     */
    private static void prepareLocalSession(AgentSoftwareLayer layer, String sid) {
        if (layer == null) return;
        String id = sid == null ? "" : sid;
        if (id.equals(sLastLocalSessionId)) return;
        layer.resetSessionSequence();
        sLastLocalSessionId = id;
        Log.i(TAG, "local session switched -> reset sequence: " + id);
    }

    /**
     * 等 chat context 真正建立后再跑本地 Agent。
     *
     * <p>initializeAsync 的 ok 只代表模型/native context 成功，不等于 chatCreate 已完成；
     * 真机上模型的 context 已 16384、chat handle 还是 0 的窗口里，AgentLoopEngine 会用
     * 4096 的兜底门拒 prompt。这里按 500ms 轮询，最多 100s（任务总超时 120s）。</p>
     */
    private static void waitForLocalReady(AgentSoftwareLayer layer, String prompt, boolean thinking,
                                          AgentSoftwareLayer.AgentCallback cb, TaskMeta meta, int attempt) {
        if (isLocalReady() && layer != null) {
            meta.refreshStartSnapshot();
            meta.markReady();
            prepareLocalSession(layer, meta.session);
            emitState("connection", "本地引擎就绪（chat context 已建立），开始推理");
            emit("── 本地引擎就绪，开始推理 ──");
            layer.processMessage(prompt, thinking);
            return;
        }
        if (attempt >= 200) {
            String detail;
            try {
                detail = "nativeValid=" + com.oilquiz.app.ai.jni.LlamaHelper.isNativeStateValid()
                        + ", contextSize=" + com.oilquiz.app.ai.jni.LlamaHelper.getContextSize();
            } catch (Throwable t) {
                detail = "state query failed: " + t;
            }
            cb.onError("本地引擎在 100s 内未就绪（" + detail + "）");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> waitForLocalReady(layer, prompt, thinking, cb, meta, attempt + 1), 500);
    }

    private static String tsStamp() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    /** 单行化 + 截断（调试台原始流 [ANSWER] 用） */
    private static String oneLine(String s, int maxLen) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() > maxLen) t = t.substring(0, maxLen) + "…";
        return t;
    }

    private static int countTokensSafe(String text) {
        try {
            return com.oilquiz.app.ai.jni.LlamaHelper.countTokens(text);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    /**
     * DEBUG-TASK-META(2026-11-08)：一次注入任务的完整统计。
     * 收尾时一次性落 result/debug/status 三个文件，避免各处 save 口径漂移。
     */
    private static final class TaskMeta {
        final Context app;
        final File out;
        final File debugOut;
        final StringBuffer sb;
        final String displayPrompt;
        final String promptType;
        final String engine;
        final String session;
        final long startMs;
        int promptTokens;
        volatile JSONObject startSnapshot;
        final JSONArray toolTraces = new JSONArray();
        final AtomicBoolean artifactsWritten = new AtomicBoolean(false);
        int contentTokens;
        int thinkingTokens;
        int toolCalls;
        long firstTokenMs = -1;
        long toolStartMs = -1;
        long readyMs = -1;
        String error;

        TaskMeta(Context app, File out, File debugOut, StringBuffer sb,
                 String displayPrompt, String promptType, String engine, String session,
                 long startMs, int promptTokens, JSONObject startSnapshot) {
            this.app = app;
            this.out = out;
            this.debugOut = debugOut;
            this.sb = sb;
            this.displayPrompt = displayPrompt;
            this.promptType = promptType;
            this.engine = engine;
            this.session = session;
            this.startMs = startMs;
            this.promptTokens = promptTokens;
            this.startSnapshot = startSnapshot;
        }

        void markFirstToken() {
            if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis();
        }

        /** 模型/chat context 真正就绪、即将提交推理的时刻。用于把模型加载从 TTFT 里拆出来。 */
        void markReady() {
            if (readyMs < 0) readyMs = System.currentTimeMillis();
        }

        /** 模型/chat context 就绪后刷新 start 快照，避免 start 里全是 0 被当成错误读数。 */
        void refreshStartSnapshot() {
            try {
                startSnapshot = AgentDebugSnapshot.capture(app);
            } catch (Throwable ignored) {
            }
        }

        void toolStart() {
            toolCalls++;
            toolStartMs = System.currentTimeMillis();
        }

        void toolEnd(String name, boolean ok, String result) {
            long now = System.currentTimeMillis();
            try {
                JSONObject t = new JSONObject();
                t.put("name", name == null ? "" : name);
                t.put("ok", ok);
                t.put("ms", toolStartMs > 0 ? now - toolStartMs : -1);
                t.put("result", oneLine(result, 1000));
                toolTraces.put(t);
            } catch (Throwable ignored) {
            }
            toolStartMs = -1;
        }

        int totalTokens() {
            return contentTokens + thinkingTokens;
        }
    }

    /** 写 status.json 的 running 快照；外部轮询在任务刚开始时也能拿到模型/探针事实。 */
    private static void writeRunningStatus(TaskMeta m) {
        try {
            JSONObject st = new JSONObject();
            st.put("schema", 2);
            st.put("status", "running");
            st.put("result", m.out.getName());
            st.put("debug", m.debugOut.getName());
            st.put("prompt_type", m.promptType);
            st.put("prompt", m.displayPrompt);
            st.put("prompt_tokens", m.promptTokens);
            st.put("engine", m.engine);
            st.put("session", m.session);
            st.put("started_at", m.startMs);
            st.put("time", nowText());
            st.put("start", m.startSnapshot);
            atomicWrite(new File(m.out.getParentFile(), "status.json"), st.toString());
        } catch (Throwable t) {
            Log.e(TAG, "running status write fail", t);
        }
    }

    /** 收尾：result（人读）+ debug.json（结构化）+ status.json（外部轮询）。 */
    private static void writeTaskArtifacts(TaskMeta m, String status, String resultText) {
        // onError 与 timeout 可能同时到达：只允许第一次落盘，避免 timeout 覆盖已完成的 done。
        if (!m.artifactsWritten.compareAndSet(false, true)) return;
        // logcat 线程还在往 sb 里 append；先给它一个短暂 drain 窗口，否则最后几十行日志会丢。
        try {
            Thread.sleep(250);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        String content = m.sb.toString();
        long endMs = System.currentTimeMillis();
        String safeResult = resultText == null ? "" : resultText;
        // 任务开始时模型可能还没加载完，countTokens 会返回 0；收尾时 tokenizer 已就绪，重算一次。
        if (m.promptTokens <= 0) {
            m.promptTokens = countTokensSafe(m.displayPrompt);
        }

        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(m.out, false), StandardCharsets.UTF_8)) {
            w.write(content);
        } catch (Throwable t) {
            Log.e(TAG, "result write fail", t);
        }

        try {
            JSONObject debug = new JSONObject();
            debug.put("schema", 2);
            debug.put("status", status);
            debug.put("engine", m.engine);
            debug.put("session", m.session);
            debug.put("started_at", m.startMs);
            debug.put("ended_at", endMs);
            debug.put("elapsed_ms", endMs - m.startMs);
            debug.put("prompt", m.displayPrompt);
            debug.put("prompt_type", m.promptType);
            debug.put("prompt_tokens", m.promptTokens);
            debug.put("content_tokens", m.contentTokens);
            debug.put("thinking_tokens", m.thinkingTokens);
            debug.put("tool_calls", m.toolCalls);
            debug.put("engine_ready_ms", m.readyMs > 0 ? m.readyMs - m.startMs : -1);
            debug.put("first_token_ms", m.firstTokenMs > 0 ? m.firstTokenMs - m.startMs : -1);
            debug.put("first_token_after_ready_ms",
                    m.firstTokenMs > 0 && m.readyMs > 0 ? m.firstTokenMs - m.readyMs : -1);
            debug.put("result_chars", safeResult.length());
            debug.put("result", safeResult);
            debug.put("error", m.error);
            debug.put("tool_traces", m.toolTraces);
            debug.put("log_lines", countLines(content));
            debug.put("log", content);
            debug.put("start", m.startSnapshot);
            debug.put("end", AgentDebugSnapshot.capture(m.app));
            atomicWrite(m.debugOut, debug.toString(2));
        } catch (Throwable t) {
            Log.e(TAG, "debug write fail", t);
        }

        try {
            JSONObject st = new JSONObject();
            st.put("schema", 2);
            st.put("status", status);
            st.put("result", m.out.getName());
            st.put("debug", m.debugOut.getName());
            st.put("prompt_type", m.promptType);
            st.put("prompt", m.displayPrompt);
            st.put("result_text", safeResult);      // 不再截断：完整文本在 status 里也可见
            st.put("result_chars", safeResult.length());
            st.put("engine", m.engine);
            st.put("session", m.session);
            st.put("started_at", m.startMs);
            st.put("ended_at", endMs);
            st.put("elapsed_ms", endMs - m.startMs);
            st.put("prompt_tokens", m.promptTokens);
            st.put("content_tokens", m.contentTokens);
            st.put("thinking_tokens", m.thinkingTokens);
            st.put("tool_calls", m.toolCalls);
            st.put("engine_ready_ms", m.readyMs > 0 ? m.readyMs - m.startMs : -1);
            st.put("first_token_ms", m.firstTokenMs > 0 ? m.firstTokenMs - m.startMs : -1);
            st.put("first_token_after_ready_ms",
                    m.firstTokenMs > 0 && m.readyMs > 0 ? m.firstTokenMs - m.readyMs : -1);
            st.put("error", m.error);
            st.put("log_lines", countLines(content));
            st.put("time", nowText());
            atomicWrite(new File(m.out.getParentFile(), "status.json"), st.toString());
        } catch (Throwable t) {
            Log.e(TAG, "status write fail", t);
        }
    }

    private static String nowText() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    /** 原子替换，避免外部轮询正好读到写了一半的 JSON。 */
    private static void atomicWrite(File target, String text) {
        try {
            File dir = target.getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(dir, target.getName() + ".tmp");
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(tmp, false), StandardCharsets.UTF_8)) {
                w.write(text);
            }
            if (!tmp.renameTo(target)) {
                if (!target.delete() || !tmp.renameTo(target)) {
                    Log.w(TAG, "atomic rename failed: " + target);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "atomic write fail: " + target, t);
        }
    }
}
