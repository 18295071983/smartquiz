package com.oilquiz.app.ai.tool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Map;

/**
 * 反向指令执行器（电脑端 → 手机）。
 *
 * <p>下沉到服务层用：不依赖 Activity，App 在后台也能弹提示 / 发通知。
 * 只做纯 UI 与轻状态，不碰文件、不跑命令、不改权限。</p>
 *
 * <p>为什么对话框在这里退化成通知：`AlertDialog` 必须要 Activity 上下文，
 * 后台没有窗口可依附；用系统级悬浮窗需要额外权限，不值得为一条提示要权限。
 * 所以——需要"当场确认"的场景，等聊天页在前台时由 Activity 侧执行器处理。</p>
 */
public final class RemoteCommandExecutor {

    private RemoteCommandExecutor() {
    }

    /**
     * 执行一条指令。
     *
     * @return 给电脑端看的结果文案
     */
    public static String execute(Context ctx, String tool, Map<String, Object> args) {
        return execute(ctx, tool, args, null);
    }

    /** 带指令 id 的重载：通知按钮回传需要它。 */
    public static String execute(Context ctx, String tool, Map<String, Object> args, String cmdId) {
        if (ctx == null || tool == null) return "no context";
        String title = str(args == null ? null : args.get("title"));
        String text = args == null ? "" : str(args.get("text") != null
                ? args.get("text") : args.get("content"));
        switch (tool) {
            case "toast":
                android.os.Handler h = new android.os.Handler(
                        android.os.Looper.getMainLooper());
                final String msg = text.isEmpty() ? title : text;
                h.post(() -> android.widget.Toast.makeText(ctx, msg,
                        android.widget.Toast.LENGTH_LONG).show());
                return "toast shown";
            case "cli": {
                // 2026-11-18：手机 CLI（电脑远程调试 App / agent 的统一命令面）。
                // 白名单解释器在 PhoneCli 内部；这里只负责跑并把 exit/stdout/stderr 拼回队列结果。
                String argv = str(args == null ? null
                        : (args.get("argv") != null ? args.get("argv") : args.get("command")));
                PhoneCli.Result r = PhoneCli.exec(ctx, argv);
                StringBuilder out = new StringBuilder();
                out.append("exit=").append(r.code).append('\n');
                if (!r.stdout.isEmpty()) out.append(r.stdout);
                if (!r.stderr.isEmpty()) out.append("\n[stderr] ").append(r.stderr);
                return out.toString().trim();
            }
            case "ui_component": {
                // C3 (2026-11-14): 带组件树的推送（args.props 里有 type）→ 交给通用渲染器开一屏；
                // 否则仍按旧的"插槽"语义渲染。这样插件推新界面不需要手机发版。
                // C5: {"view":"demo"} -> 用插件下发的视图定义打开
                Object viewName = args == null ? null : args.get("view");
                if (viewName instanceof String && !((String) viewName).trim().isEmpty()) {
                    com.oilquiz.app.ui.activity.RemoteViewActivity.openNamed(ctx, ((String) viewName).trim());
                    return "view opened by name";
                }
                Object viewRaw = args == null ? null : args.get("props");
                org.json.JSONObject view = null;
                try {
                    if (viewRaw instanceof java.util.Map) {
                        view = new org.json.JSONObject((java.util.Map<?, ?>) viewRaw);
                    } else if (viewRaw instanceof org.json.JSONObject) {
                        view = (org.json.JSONObject) viewRaw;
                    } else if (viewRaw instanceof String) {
                        String s = ((String) viewRaw).trim();
                        if (s.startsWith("{")) view = new org.json.JSONObject(s);
                    }
                } catch (Throwable t) {
                    android.util.Log.w("RemoteCommandExecutor", "props parse failed: " + t.getMessage());
                }
                if (view != null && view.optString("type", "").length() > 0) {
                    android.util.Log.i("RemoteCommandExecutor", "opening plugin view type=" + view.optString("type"));
                    com.oilquiz.app.ui.activity.RemoteViewActivity.open(ctx, view);
                    return "view opened";
                }
                // 语义对齐（2026-11-13）：这个名字以前直接退化成通知，名不副实。
                // 现在真的渲染成"输入区上方插槽"（与插件 ui/slot 同一套 DockSlotBus）；
                // id/title/detail 为空时仍退化成通知，避免静默丢失。
                String sid = str(args == null ? null : args.get("id"));
                if (sid == null || sid.trim().isEmpty()) sid = "remote_cmd";
                // §22：默认落 p5（后台/统计档），电脑端显式给 priority 时仍以数值为准（旧帧兼容）
                int pri = com.oilquiz.app.ai.chat.status.SlotPriorities.P5;
                Object rawPri = args == null ? null : args.get("priority");
                if (rawPri instanceof Number) pri = ((Number) rawPri).intValue();
                if (title.isEmpty() && text.isEmpty()) {
                    com.oilquiz.app.ai.chat.status.DockSlotBus.remove(sid);
                    return "slot removed";
                }
                com.oilquiz.app.ai.chat.status.DockSlotBus.updateLocal(
                        new com.oilquiz.app.ai.chat.status.DockSlotBus.Slot(
                                sid, pri, title, text,
                                str(args == null ? null : args.get("action")), false));
                return "slot shown";
            }
            case "dialog":
                // 后台没有 Activity：退化成通知，至少让人能看到
                return notify(ctx, title.isEmpty() ? "来自电脑端" : title, text, cmdId, args == null ? null : args.get("actions"));
            case "notification":
            case "tool_call": {
                // 2026-11-18：电脑直接调手机工具（不经过手机 agent 引擎）。
                // 安全：默认白名单 + 每次手机弹窗确认（30s 不响应=拒绝）。
                String name = str(args == null ? null
                        : (args.get("name") != null ? args.get("name") : args.get("tool"))).trim();
                if (name.isEmpty()) return "tool_call: name required";
                if (TOOL_CALL_DENY.contains(name)) {
                    return "blocked: " + name + " 在手机端默认禁止远程调用";
                }
                if (!TOOL_CALL_ALLOW.contains(name)) {
                    return "blocked: " + name + " 不在手机端远程调用白名单（如需放行请在手机上设置）";
                }
                Map<String, Object> toolArgs = new java.util.HashMap<>();
                Object rawArgs = args == null ? null : args.get("args");
                try {
                    if (rawArgs instanceof Map) {
                        for (Map.Entry<?, ?> e : ((Map<?, ?>) rawArgs).entrySet()) {
                            toolArgs.put(String.valueOf(e.getKey()), e.getValue());
                        }
                    } else if (rawArgs instanceof String && !((String) rawArgs).trim().isEmpty()) {
                        org.json.JSONObject o = new org.json.JSONObject((String) rawArgs);
                        java.util.Iterator<String> it = o.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            toolArgs.put(k, o.opt(k));
                        }
                    }
                } catch (Throwable t) {
                    return "tool_call: 参数不是合法 JSON: " + t;
                }
                android.util.Log.i("RemoteCommandExecutor", "tool_call name=" + name
                        + " args=" + toolArgs + " allow=" + TOOL_CALL_ALLOW.contains(name));
                Boolean allowed = confirmToolCall(ctx, name, toolArgs);
                android.util.Log.i("RemoteCommandExecutor", "tool_call confirm=" + allowed);
                if (allowed == null) return "denied: 手机端 30 秒内未确认（或当前没有前台页面）";
                if (!allowed) return "denied by user";
                long t0 = System.currentTimeMillis();
                try {
                    AIToolResult r = AIToolManager.getInstance(ctx).executeTool(name, toolArgs);
                    long ms = System.currentTimeMillis() - t0;
                    boolean ok = r == null || r.isSuccess();
                    PhoneEventReporter.tool(name, ok, ms,
                            ok ? null : (r == null ? "" : r.getErrorMessage()));
                    String out = r == null ? "" : str(r.getResult());
                    return out.length() > 200_000 ? out.substring(0, 200_000) + "\n…(truncated)" : out;
                } catch (Throwable t) {
                    PhoneEventReporter.tool(name, false, System.currentTimeMillis() - t0, String.valueOf(t));
                    return "tool_call failed: " + t;
                }
            }
            case "agent_ask": {
                // 2026-11-18：电脑驱动手机 agent（把 prompt 塞进手机聊天页的发送管线，
                // 走手机自己的系统提示/工具/模型，等这一轮结束回传最终文本）。
                String prompt = str(args == null ? null
                        : (args.get("prompt") != null ? args.get("prompt") : args.get("text"))).trim();
                if (prompt.isEmpty()) return "agent_ask: prompt required";
                if (!RemoteDshTool.isAgentDriveEnabled(ctx)) {
                    return "blocked: 手机端未开启「允许电脑驱动 Agent」（远程连接页可开）";
                }
                android.app.Activity act = com.oilquiz.app.SmartQuizApplication.getCurrentActivity();
                if (!(act instanceof com.oilquiz.app.ui.activity.AIChatActivity)) {
                    return "denied: 需要手机聊天页在前台（当前不是）";
                }
                if (!PhoneAgentBridge.begin()) return "busy: 已有一条 agent 请求在跑";
                try {
                    final com.oilquiz.app.ui.activity.AIChatActivity chat =
                            (com.oilquiz.app.ui.activity.AIChatActivity) act;
                    chat.runOnUiThread(() -> chat.submitExternalPrompt(prompt));
                    String reply = PhoneAgentBridge.await(120_000);
                    if (reply == null) {
                        String err = PhoneAgentBridge.lastError();
                        return err != null ? "agent 失败: " + err
                                : "timeout: 手机 agent 120 秒内没有完成";
                    }
                    return reply;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "agent_ask: interrupted";
                } finally {
                    PhoneAgentBridge.finish();
                }
            }
            case "reminder":
            default:
                return notify(ctx, title.isEmpty() ? "来自电脑端" : title, text, cmdId, args == null ? null : args.get("actions"));
        }
    }

    private static String notify(Context ctx, String title, String text) {
        return notify(ctx, title, text, null, null);
    }

    /**
     * 发通知；带 actions 时挂按钮，点了把选择回传给电脑端（见 {@link RemoteCmdActionReceiver}）。
     *
     * @param cmdId   反向指令 id（按钮回传时带上）
     * @param actions 形如 [{"label":"同意","value":"yes"}]，可为空
     */
    static String notify(Context ctx, String title, String text, String cmdId, Object actions) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return "no notification service";
            String ch = "dsh_cmd";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(
                        ch, "电脑端消息", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(ctx, ch)
                    : new Notification.Builder(ctx);
            b.setContentTitle(title)
                    .setContentText(text == null ? "" : text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true);
            // 动作按钮：每个按钮一个广播，点了直接回传。
            // 注意：args 是从 JSONObject 转过来的，actions 在这边是 org.json.JSONArray，
            // 不是 java.util.List —— 只判断 List 的话按钮永远加不上（真机现象：通知没按钮）。
            java.util.List<Object> actList = new java.util.ArrayList<>();
            if (actions instanceof org.json.JSONArray) {
                org.json.JSONArray ja = (org.json.JSONArray) actions;
                for (int i = 0; i < ja.length(); i += 1) actList.add(ja.optJSONObject(i));
            } else if (actions instanceof java.util.List) {
                actList.addAll((java.util.List<?>) actions);
            }
            if (!actList.isEmpty() && cmdId != null && !cmdId.isEmpty()) {
                int req = 0;
                for (Object o : actList) {
                    if (o instanceof org.json.JSONObject) {
                        org.json.JSONObject jo = (org.json.JSONObject) o;
                        String label = jo.optString("label", "");
                        String value = jo.optString("value", "");
                        addActionButton(ctx, b, cmdId, label, value, req++);
                    } else if (o instanceof Map) {
                        Map<?, ?> m = (Map<?, ?>) o;
                        addActionButton(ctx, b, cmdId, str(m.get("label")), str(m.get("value")), req++);
                    }
                }
            }
            nm.notify((int) (cmdId != null && !cmdId.isEmpty()
                    ? (cmdId.hashCode() & 0xFFFFFF)
                    : (System.currentTimeMillis() & 0xFFFFFF)), b.build());
            return "notification shown";
        } catch (Throwable t) {
            return "notify failed: " + t;
        }
    }

    /** 默认允许远程调用的工具（只读/展示类）；不在两表里的一律拒绝。 */
    private static final java.util.Set<String> TOOL_CALL_ALLOW = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "ui_component", "screen_capture", "file_reader", "web_render", "time_date",
                    "calculator", "text_tools", "unit_converter", "screen_watch", "ocr_recognize"));

    /** 明确禁止（shell / 写文件 / 导出 / 应用操作等）。 */
    private static final java.util.Set<String> TOOL_CALL_DENY = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "linux_shell", "python_execute", "file_generator", "export_apk", "app_operation",
                    "system_resource", "system_connect", "git", "pip_install", "database"));

    /**
     * 电脑请求调用手机工具时的确认弹窗（主线程）。
     *
     * @return true=用户允许；false=拒绝；null=30 秒内无响应或没有前台页面
     */
    private static Boolean confirmToolCall(Context ctx, String name, Map<String, Object> args) {
        android.app.Activity act = com.oilquiz.app.SmartQuizApplication.getCurrentActivity();
        android.util.Log.i("RemoteCommandExecutor", "confirmToolCall act=" + act);
        if (act == null) return null;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Boolean> result =
                new java.util.concurrent.atomic.AtomicReference<>(null);
        String digest = args == null || args.isEmpty() ? "（无参数）" : args.toString();
        if (digest.length() > 300) digest = digest.substring(0, 300) + "…";
        final String msg = "工具：" + name + "\n参数：" + digest
                + "\n\n电脑请求在你这台手机上执行这个工具，允许吗？";
        act.runOnUiThread(() -> {
            try {
                com.oilquiz.app.ui.modal.AppDialog.builder(act)
                        .setTitle("电脑请求调用手机工具")
                        .setMessage(msg)
                        .cancelable(false)
                        .setPositiveButton("允许", (d, w) -> {
                            result.set(true);
                            latch.countDown();
                        })
                        .setNegativeButton("拒绝", (d, w) -> {
                            result.set(false);
                            latch.countDown();
                        })
                        .onDismiss(d -> latch.countDown())
                        .show();
            } catch (Throwable t) {
                latch.countDown();
            }
        });
        try {
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return result.get();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 给通知加一颗按钮（点了走 {@link RemoteCmdActionReceiver} 回传）。 */
    private static void addActionButton(Context ctx, Notification.Builder b,
                                        String cmdId, String label, String value, int req) {
        if (label == null || label.isEmpty()) return;
        Intent it = new Intent(ctx, RemoteCmdActionReceiver.class);
        it.setAction(RemoteCmdActionReceiver.ACTION);
        it.putExtra(RemoteCmdActionReceiver.EXTRA_CMD_ID, cmdId);
        it.putExtra(RemoteCmdActionReceiver.EXTRA_VALUE, value == null ? "" : value);
        it.putExtra(RemoteCmdActionReceiver.EXTRA_LABEL, label);
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                ctx, req, it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | android.app.PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(null, label, pi).build());
    }
}
