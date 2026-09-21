package com.oilquiz.app.infra;

/*
 * ============================================================================
 * AgentDebugBridge —— 应用内在线 Agent 的正式外部注入通道（长期保留的 debug 能力）
 * ============================================================================
 * 用途：允许外部（adb shell）通过显式广播，注入 prompt 驱动应用内在线 Agent
 *       （OnlineAgentEngine，完整工具链）执行一次任务，结果落盘供拉取。
 *       用于自动化联动测试、外部编排、诊断。
 *
 * 安全设计（四层防护）：
 *   1. 授权码（token）：首次触发时随机生成 16 位 hex，存入 SharedPreferences，
 *      并在 logcat 打印（TAG=AgentDebugBridge，行含 "token="）。注入必须携带
 *      --es token <码>，不匹配直接拒绝。
 *   2. 通道开关（enabled）：默认 OFF。必须先带正确 token 发送 enable 广播打开；
 *      关闭状态下拒绝一切注入。开关状态持久化（install -r 保留）。
 *   3. 显式广播：Android 8+ 隐式广播无法触达静态 receiver，必须用 -n 指定组件；
 *      同时避免误伤系统广播分发。
 *   4. 执行隔离：每次独立引擎实例 + 随机会话 ID（历史独立文件），不污染主对话；
 *      并发互斥（同时仅一个任务）；120s 超时自动释放锁；只写结果文件，不碰业务数据。
 *
 * 用法（见 DEBUG_AGENT_BRIDGE.md）：
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
 *            [--ei max_tokens 16384] [--ez thinking true]
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
import com.oilquiz.app.ai.agent.online.OnlineAgentEngine;
import com.oilquiz.app.ai.agent.online.OnlineToolManager;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;

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
        final StringBuilder sb = new StringBuilder();

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
            logcatProc[0] = Runtime.getRuntime().exec(
                new String[]{"logcat", "-v", "time", "-s",
                    "OnlineAgentEngine:V", "OnlinePromptBuilder:V",
                    "OnlineToolManager:V", "AIService:V",
                    "OnlineInferenceService:V",
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

        try {
            final int[] attempt = {0};
            final boolean[] retrying = {false};
            final int[] outTokens = {0};   // 本轮已输出的 token 数（思考+正文）
            final String sid = (session != null && !session.isEmpty()) ? session : "ext_" + ts;
            // 复用同一引擎实例重试：保留 messageHistory/thinkingChain，断点续传（近似）
            final OnlineToolManager[] tmHolder = {null};
            final OnlineAgentEngine[] engineHolder = {null};

            final Runnable[] executeTask = new Runnable[1];
            executeTask[0] = new Runnable() {
                @Override public void run() {
                    attempt[0]++;
                    outTokens[0] = 0;   // 每轮重新计数（部分输出按轮判定）
                    if (engineHolder[0] == null) {
                        tmHolder[0] = new OnlineToolManager(app);
                        OnlineToolManager.setInstance(tmHolder[0]);
                        engineHolder[0] = new OnlineAgentEngine(app, tmHolder[0]);
                        engineHolder[0].setSessionId(sid);
                    }
                    emitState("connection", "引擎就绪，会话隔离=" + sid
                            + (attempt[0] > 1 ? "（第 " + attempt[0] + " 次尝试，断点续传）" : ""));

                    AgentCallback cb = new AgentCallback() {
                        @Override public void onToken(String token) {
                            outTokens[0]++;
                            sb.append("TOKEN: ").append(token).append('\n');
                            emit("TOKEN: " + token);
                        }
                        @Override public void onThinkingToken(String token) {
                            outTokens[0]++;
                            sb.append("THINK: ").append(token).append('\n');
                            emit("THINK: " + token);
                        }
                        @Override public void onThinkingEnd() {
                            sb.append("-- think end --\n");
                            emit("── 思考结束 ──");
                        }
                        @Override public void onToolCallStart(String id, String name, String args) {
                            sb.append("[TOOL] ").append(name).append(' ').append(args).append('\n');
                            emit("▶ 工具调用: " + name + " " + args);
                        }
                        @Override public void onToolCallComplete(String id, String name, OnlineToolResult r) {
                            sb.append("[TOOL-OK] ").append(name).append(" -> ").append(r).append('\n');
                            emit("✔ 工具完成: " + name + " -> " + r);
                        }
                        @Override public void onStepUpdate(String step, String detail) {
                            sb.append("[STEP] ").append(step).append(' ').append(detail).append('\n');
                            emit("[STEP] " + step + " " + detail);
                        }
                        @Override public void onComplete(String fullText) {
                            sb.append("=== COMPLETE ===\n").append(fullText).append('\n');
                            emitState("status", "✅ 完成");
                            emit("── 执行完成 ──");
                            emitRaw("[RESULT] done result=" + out.getName() + " " + tsStamp());
                            emitRaw("[ANSWER] " + oneLine(fullText, 300));
                            save("done", fullText); Log.i(TAG, "done -> " + out); release();
                        }
                        @Override public void onError(String error) {
                            sb.append("=== ERROR ===\n").append(error).append('\n');
                            emitState("status", "❌ 错误: " + error);
                            emit("── 执行出错: " + error + " ──");
                            if (outTokens[0] > 0) {
                                // 已有输出（思考/正文任意 token）：连接是通的，中断属流式瞬时故障。
                                // 不粗暴判为网络问题重试，保留已生成内容按「部分完成」收尾。
                                String partialTail = sb.length() > 0
                                        ? sb.substring(Math.max(0, sb.length() - 600))
                                        : "";
                                sb.append("=== PARTIAL ===\n")
                                    .append("连接中断，已保留部分输出（已输出 token=").append(outTokens[0]).append("）\n");
                                emitState("status", "⚠️ 连接中断，已保留部分输出");
                                emit("── 连接中断，已保留部分输出 ──");
                                emitRaw("[RESULT] partial result=" + out.getName() + " " + tsStamp());
                                emitRaw("[ANSWER] 部分输出：" + oneLine(partialTail, 300));
                                Log.w(TAG, "partial (tokens=" + outTokens[0] + ", err=" + error + ") -> " + out);
                                save("partial", "连接中断，已保留部分输出（token=" + outTokens[0] + "）。\n"
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
                            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(out, true), StandardCharsets.UTF_8)) {
                                w.write(sb.toString());
                            } catch (Exception e) { Log.e(TAG, "save fail", e); }
                            writeStatus(status, out, displayPrompt, promptType, resultText);
                        }
                        void release() {
                            if (logcatProc[0] != null) logcatProc[0].destroy();
                            running.set(false); pr.finish();
                        }
                    };

                    engineHolder[0].setCallback(cb);
                    Log.i(TAG, "execute#" + attempt[0] + ": " + prompt + " (session=" + sid + ", maxTokens=" + maxTokens + ", thinking=" + thinking + ")");
                    sb.append(fullVisible ? "PROMPT: " : "PROMPT_SUMMARY: ")
                            .append(displayPrompt).append('\n');
                    writeStatus("running", out, displayPrompt, promptType, "");
                    // 外部注入模式：加系统提示前缀，优化行为
            String effectivePrompt = "[调试模式] 你现在处于外部调试通道。请：\n"
                    + "1. 直接输出完整答案，不要反问用户（如\"你要出门吗？\"）\n"
                    + "2. 不要使用 ui_component 工具，直接输出文本答案\n"
                    + "3. 减少不必要的工具调用，简单问题直接回答\n\n"
                    + "用户指令：" + prompt;
            engineHolder[0].execute(effectivePrompt, maxTokens, thinking);
                }
            };

            executeTask[0].run();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (running.get() && !retrying[0]) {
                    Log.w(TAG, "timeout " + TASK_TIMEOUT_MS + "ms, 释放并发锁");
                    emitState("status", "⏱ 超时释放并发锁");
                    emitRaw("[RESULT] timeout result=" + out.getName() + " " + tsStamp());
                    writeStatus("timeout", out, displayPrompt, promptType, "任务超时（120s），已释放并发锁，结果不完整");
                    running.set(false);
                    pr.finish();
                }
            }, TASK_TIMEOUT_MS);
        } catch (Throwable t) {
            Log.e(TAG, "init fail", t);
            sb.append("INIT ERROR: ").append(t).append('\n');
            emitState("status", "❌ 初始化失败: " + t);
            emit("── 初始化失败: " + t + " ──");
            emitRaw("[RESULT] init_error " + t + " " + tsStamp());
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            } catch (Exception ignored) {}
            writeStatus("error", out, displayPrompt, promptType, "初始化失败: " + t);
            running.set(false);
            pr.finish();
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

    /**
     * 状态回执文件（外部轮询标准接口）：agent_bridge/status.json
     * PC 端：adb shell cat /sdcard/Android/data/com.oilquiz.app/files/agent_bridge/status.json
     * 或轮询该文件判断任务 running/done/error/timeout。
     */
    private static void writeStatus(String status, File resultFile, String displayPrompt, String promptType, String resultText) {
        try {
            File dir = resultFile.getParentFile();
            if (dir == null) return;
            String promptSafe = (displayPrompt == null ? "" : displayPrompt)
                    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            String rt = (resultText == null ? "" : resultText)
                    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            if (rt.length() > 2000) rt = rt.substring(0, 2000) + "…[截断]";
            String json = "{\"status\":\"" + status + "\",\"result\":\""
                    + resultFile.getName() + "\",\"prompt_type\":\"" + promptType
                    + "\",\"prompt\":\"" + promptSafe
                    + "\",\"result_text\":\"" + rt
                    + "\",\"time\":\"" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\"}\n";
            java.io.FileWriter w = new java.io.FileWriter(new File(dir, "status.json"));
            try {
                w.write(json);
            } finally {
                w.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "status write fail", e);
        }
    }
}
