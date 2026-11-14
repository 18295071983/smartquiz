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
                int pri = 70;
                Object rawPri = args == null ? null : args.get("priority");
                if (rawPri instanceof Number) pri = ((Number) rawPri).intValue();
                if (title.isEmpty() && text.isEmpty()) {
                    com.oilquiz.app.ai.chat.status.DockSlotBus.remove(sid);
                    return "slot removed";
                }
                com.oilquiz.app.ai.chat.status.DockSlotBus.update(
                        new com.oilquiz.app.ai.chat.status.DockSlotBus.Slot(
                                sid, pri, title, text,
                                str(args == null ? null : args.get("action")), false));
                return "slot shown";
            }
            case "dialog":
                // 后台没有 Activity：退化成通知，至少让人能看到
                return notify(ctx, title.isEmpty() ? "来自电脑端" : title, text, cmdId, args == null ? null : args.get("actions"));
            case "notification":
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
