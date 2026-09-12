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

    /** 调试页监听器：onEvent 为日志行，onState 为状态字段（connection/status） */
    public interface BridgeListener {
        void onEvent(String line);
        void onState(String key, String value);
    }

    private static volatile BridgeListener listener;

    /** 注册/注销调试页监听（Activity onResume/onPause 调用） */
    public static void setListener(BridgeListener l) {
        listener = l;
    }

    /** 推送日志行到调试页 */
    private static void emit(String line) {
        BridgeListener l = listener;
        if (l != null) l.onEvent(line);
    }

    /** 推送状态到调试页 */
    private static void emitState(String key, String value) {
        BridgeListener l = listener;
        if (l != null) l.onState(key, value);
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
            return;
        }

        if (ACTION_ENABLE.equals(action)) {
            boolean on = intent.getBooleanExtra("enable", false);
            setEnabled(app, on);
            Log.i(TAG, "通道开关 -> " + (on ? "ON" : "OFF"));
            return;
        }

        if (!ACTION_EXEC.equals(action)) return;
        if (!isEnabled(app)) {
            Log.w(TAG, "拒绝: 通道未开启（先发 AGENT_ENABLE enable=true）");
            return;
        }

        String prompt = intent.getStringExtra("prompt");
        if (prompt == null || prompt.trim().isEmpty()) {
            Log.w(TAG, "拒绝: prompt 为空");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "拒绝: 已有任务执行中");
            return;
        }

        final PendingResult pr = goAsync();
        final String session = intent.getStringExtra("session");
        final int maxTokens = intent.getIntExtra("max_tokens", DEFAULT_MAX_TOKENS);
        final boolean thinking = intent.getBooleanExtra("thinking", false);
        final String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        final File outDir = new File(app.getExternalFilesDir(null), "agent_bridge");
        outDir.mkdirs();
        final File out = new File(outDir, "result_" + ts + ".txt");
        final StringBuilder sb = new StringBuilder();

        // 自动跳转调试控制台（实时显示连通状态与执行过程）
        openDebugActivity(app);
        emitState("connection", "注入已接收，正在启动引擎…");
        emit("── 外部注入接收 ──");
        emit("PROMPT: " + prompt);

        try {
            OnlineToolManager tm = new OnlineToolManager(app);
            OnlineToolManager.setInstance(tm);
            OnlineAgentEngine engine = new OnlineAgentEngine(app, tm);
            String sid = (session != null && !session.isEmpty()) ? session : "ext_" + ts;
            engine.setSessionId(sid);
            emitState("connection", "引擎就绪，会话隔离=" + sid);

            AgentCallback cb = new AgentCallback() {
                @Override public void onToken(String token) {
                    sb.append("TOKEN: ").append(token).append('\n');
                    emit("TOKEN: " + token);
                }
                @Override public void onThinkingToken(String token) {
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
                    save(); Log.i(TAG, "done -> " + out); release();
                }
                @Override public void onError(String error) {
                    sb.append("=== ERROR ===\n").append(error).append('\n');
                    emitState("status", "❌ 错误: " + error);
                    emit("── 执行出错: " + error + " ──");
                    save(); Log.e(TAG, "error: " + error + " -> " + out); release();
                }
                void save() {
                    try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(out, true), StandardCharsets.UTF_8)) {
                        w.write(sb.toString());
                    } catch (Exception e) { Log.e(TAG, "save fail", e); }
                }
                void release() { running.set(false); pr.finish(); }
            };

            engine.setCallback(cb);
            Log.i(TAG, "execute: " + prompt + " (session=" + sid + ", maxTokens=" + maxTokens + ", thinking=" + thinking + ")");
            sb.append("PROMPT: ").append(prompt).append('\n');
            engine.execute(prompt, maxTokens, thinking);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (running.get()) {
                    Log.w(TAG, "timeout " + TASK_TIMEOUT_MS + "ms, 释放并发锁");
                    emitState("status", "⏱ 超时释放并发锁");
                    running.set(false);
                    pr.finish();
                }
            }, TASK_TIMEOUT_MS);
        } catch (Throwable t) {
            Log.e(TAG, "init fail", t);
            sb.append("INIT ERROR: ").append(t).append('\n');
            emitState("status", "❌ 初始化失败: " + t);
            emit("── 初始化失败: " + t + " ──");
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            } catch (Exception ignored) {}
            running.set(false);
            pr.finish();
        }
    }
}
